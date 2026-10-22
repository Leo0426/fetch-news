package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.ai.AiConfig;
import app.ai.AiConfigStore;
import app.ai.ArticleExtractor;
import app.ai.ArticleSummarizer;
import app.ai.OllamaClient;
import app.ai.SummaryProgress;
import app.core.CacheService;
import app.core.FeedItem;
import app.core.RouteConfigStore;
import app.core.RouteRegistry;
import app.reader.ArticlePageView;
import app.store.FeedStore;
import app.store.SummaryStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class ReaderArticleTest {
    @TempDir Path directory;
    private FeedStore store;
    private AdminTemplateRenderer renderer;

    @BeforeEach
    void setup() throws Exception {
        var mapper = new ObjectMapper();
        store = new FeedStore(directory.resolve("feed.db"), mapper);
        var runtime = new AppRuntime(new CacheService(), new RouteRegistry(List.of()),
                new RouteConfigStore(directory.resolve("routes.json"), mapper));
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        renderer = new AdminTemplateRenderer(engine, store, runtime, new AppInfo());
    }

    @AfterEach
    void close() throws Exception { store.close(); }

    @Test
    void opensSavedTextWithNoReadMutationOrAiDependency() throws Exception {
        String link = "https://example.com/正文?a=1&b=2";
        save(link, "Saved title <script>bad()</script>", "<h2>正文标题</h2><p>离线也能阅读的保存内容。</p>");
        store.saveSummary(link, "<img src=x onerror=bad()> 摘要", "fixture");
        var response = reader(store).article(link, "/reader/unread?source=%2Farticles&before=42");
        assertEquals(200, response.getStatusCode().value());
        var page = Jsoup.parse(response.getBody());
        assertTrue(page.text().contains("离线也能阅读的保存内容"));
        assertTrue(page.text().contains("Saved title <script>bad()</script>"));
        assertTrue(page.text().contains("<img src=x onerror=bad()> 摘要"));
        assertTrue(page.select("main img, main script").isEmpty());
        assertTrue(page.select("a[href='/reader/unread?source=%2Farticles&before=42']").size() > 0);
        assertFalse(store.isRead(link));
        assertFalse(store.isBookmarked(link));
        assertTrue(store.loadSummaryStatuses(List.of(link)).get(link).success());
    }

    @Test
    void bookmarkWritesUseDesiredStateAndCanonicalMetadata() throws Exception {
        String link = "https://example.com/saved";
        save(link, "Canonical title", "body");
        var resource = reader(store);
        assertEquals("bookmarked", resource.setArticleBookmark(link, true).getBody());
        var firstBookmark = store.loadBookmarks().getFirst();
        assertEquals("bookmarked", resource.setArticleBookmark(link, true).getBody());
        assertEquals(firstBookmark.bookmarkedAt(), store.loadBookmarks().getFirst().bookmarkedAt());
        assertEquals(1, store.loadBookmarks().size());
        assertEquals("Canonical title", store.loadBookmarks().getFirst().title());
        assertEquals("/articles", store.loadBookmarks().getFirst().routePath());
        assertEquals("removed", resource.setArticleBookmark(link, false).getBody());
        assertEquals("removed", resource.setArticleBookmark(link, false).getBody());
        assertFalse(store.isBookmarked(link));
        assertFalse(store.isRead(link));
        assertEquals(404, resource.setArticleBookmark("https://example.com/missing", true).getStatusCode().value());
    }

    @Test
    void errorsAreDistinctAndReturnDestinationsStayInsideReader() throws Exception {
        var resource = reader(store);
        assertEquals(400, resource.article("javascript:alert(1)", "https://evil.invalid").getStatusCode().value());
        var invalid = Jsoup.parse(resource.article("javascript:alert(1)", "/reader\\evil").getBody());
        assertTrue(invalid.select("a[href='/reader/unread']").size() > 0);
        assertTrue(invalid.select("a[href^='javascript:']").isEmpty());
        String missing = "https://example.com/missing";
        store.saveBookmark(missing, "Orphan bookmark", "/old");
        var absent = resource.article(missing, "/reader/bookmarks");
        assertEquals(404, absent.getStatusCode().value());
        assertTrue(Jsoup.parse(absent.getBody()).select("a[href='https://example.com/missing']").size() > 0);
        assertTrue(store.isBookmarked(missing));
        assertEquals(503, reader(null).article(missing, "").getStatusCode().value());
        assertEquals(503, reader(null).setArticleBookmark(missing, true).getStatusCode().value());
        assertEquals(400, resource.setArticleBookmark("javascript:bad()", true).getStatusCode().value());
        store.close();
        assertEquals(500, resource.article(missing, "").getStatusCode().value());
        assertEquals(500, resource.setArticleBookmark(missing, true).getStatusCode().value());
    }

    @Test
    void sourceExtractAndSummaryOnlyRemainClearlyDistinguished() throws Exception {
        String source = "https://example.com/source", extracted = "https://example.com/extracted";
        String summarized = "https://example.com/summary", empty = "https://example.com/empty";
        save(source, "Source", "<p>Source passage</p>");
        save(extracted, "Extracted", "<script>hidden</script><img src='https://external.invalid/tracker'>");
        save(summarized, "Summary only", null);
        save(empty, "Empty", " ");
        store.saveExtract(source, "Must not replace source", "fixture");
        store.saveExtract(extracted, "AI literal <script>example()</script>", "fixture");
        store.saveSummary(summarized, "Only a summary", "fixture");
        var original = Jsoup.parse(reader(store).article(source, "").getBody());
        assertEquals("Source passage", original.select(".article-prose").text());
        assertTrue(original.text().contains("可能是节选"));
        var fallback = Jsoup.parse(reader(store).article(extracted, "").getBody());
        assertTrue(fallback.text().contains("提取并保存的内容，可能是节选"));
        assertTrue(fallback.select(".article-prose").text().contains("<script>example()</script>"));
        assertTrue(fallback.select(".article-prose script,.article-prose img").isEmpty());
        var summary = Jsoup.parse(reader(store).article(summarized, "").getBody());
        assertEquals("Only a summary", summary.select("[data-article-summary-text]").text());
        assertTrue(summary.select(".article-prose").isEmpty());
        assertTrue(summary.text().contains("暂无已保存内容"));
        var missing = Jsoup.parse(reader(store).article(empty, "").getBody());
        assertEquals("none", missing.select("[data-summary-state]").attr("data-summary-state"));
        assertTrue(missing.select("[data-article-summary-text]:not([hidden])").isEmpty());
        assertTrue(missing.text().contains("暂无已保存内容"));
    }

    @Test
    void summaryCompletedAfterArticleSnapshotIsIncludedInPageView() throws Exception {
        String link = "https://example.com/just-completed";
        save(link, "Saved", "Saved content");
        var snapshot = store.loadArticle(link);
        var progress = new SummaryProgress("success", "Newly completed summary", null);
        var page = ArticlePageView.from(snapshot, "/reader/unread", true, progress);
        assertTrue(page.aiEnabled());
        assertEquals(progress, page.summaryProgress());
        assertEquals("Newly completed summary", page.item().summary());
        var compatible = ArticlePageView.from(snapshot, "/reader/unread");
        assertFalse(compatible.aiEnabled());
        assertEquals("none", compatible.summaryProgress().state());
        var missing = ArticlePageView.from(snapshot, "/reader/unread", true,
                new SummaryProgress("success", null, null));
        assertEquals("unknown", missing.summaryProgress().state());
        assertFalse(missing.item().hasSummary());
    }

    @ParameterizedTest
    @CsvSource({"none,true", "queued,true", "running,true", "error,true", "success,true",
            "none,false", "queued,false", "running,false", "error,false", "success,false"})
    void articleReloadRestoresSummaryStateWithoutStartingWork(String state, boolean enabled) throws Exception {
        String link = "https://example.com/summary-state";
        save(link, "Saved article", "Original saved body");
        var mapper = new ObjectMapper();
        var config = new AiConfigStore(directory.resolve("ai.json"), mapper);
        config.save(new AiConfig("http://127.0.0.1:1", "fixture", enabled));
        var model = new OllamaClient(config, mapper) {
            @Override public Generation generateWithMetadata(String prompt) {
                throw new AssertionError("Reading an article must not start inference");
            }
        };
        try (var summarizer = new ArticleSummarizer(model, store, new ArticleExtractor(model, store,
                url -> { throw new AssertionError("Reading saved content must not fetch the article"); }), config)) {
            Instant started = Instant.parse("2026-01-01T00:00:00Z");
            switch (state) {
                case "queued" -> store.enqueueSummary(link, started, false);
                case "running" -> {
                    store.enqueueSummary(link, started, false);
                    store.claimNextSummary(started, "fixture");
                }
                case "error" -> store.markSummaryFinished(link, started, started.plusSeconds(1),
                        "fixture", "Provider rejected Authorization: Bearer sk-fixture-private-token");
                case "success" -> store.saveSummary(link, "Cached <script>summary</script>", "fixture");
                default -> { }
            }
            var before = store.loadSummaryStatuses(List.of(link));
            var resource = new ReaderResource(store, null, renderer, summarizer, config);
            for (int reload = 0; reload < 2; reload++) {
                var response = resource.article(link, "/reader/unread");
                assertEquals(200, response.getStatusCode().value());
                assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
                var page = Jsoup.parse(response.getBody());
                assertEquals("Original saved body", page.select(".article-prose").text());
                assertEquals(state, page.select("[data-summary-state]").attr("data-summary-state"));
                assertEquals(Boolean.toString(enabled), page.select("[data-ai-enabled]").attr("data-ai-enabled"));
                if (state.equals("success")) assertTrue(page.text().contains("Cached <script>summary</script>"));
                if (state.equals("error")) assertTrue(page.text().contains("AI Provider"));
                assertTrue(page.select(".article-summary script").isEmpty());
                assertFalse(response.getBody().contains("sk-fixture-private-token"));
                assertFalse(response.getBody().contains("Authorization"));
                assertEquals(before, store.loadSummaryStatuses(List.of(link)));
                assertFalse(store.isRead(link));
                assertFalse(store.isBookmarked(link));
            }
        }
    }

    @Test
    void summaryStatusReadFailurePreservesArticleAndHidesRawError() throws Exception {
        var mapper = new ObjectMapper();
        String link = "https://example.com/status-unavailable";
        try (var unavailable = new FeedStore(directory.resolve("unavailable.db"), mapper) {
            @Override public synchronized Map<String, SummaryStatus> loadSummaryStatuses(List<String> links)
                    throws SQLException {
                throw new SQLException("Failed with Authorization Bearer sk-fixture-do-not-expose");
            }
        }; var summarizer = new ArticleSummarizer(null, unavailable, null, null)) {
            unavailable.saveItems("/articles", List.of(new FeedItem("Saved", link,
                    "Still readable saved body", null, null, List.of())));
            var resource = new ReaderResource(unavailable, null, renderer, summarizer, null);
            var response = resource.article(link, "/reader/unread");
            assertEquals(200, response.getStatusCode().value());
            var page = Jsoup.parse(response.getBody());
            assertEquals("Still readable saved body", page.select(".article-prose").text());
            assertEquals("unknown", page.select("[data-summary-state]").attr("data-summary-state"));
            assertFalse(page.select("[data-article-summary-check]").hasAttr("hidden"));
            assertTrue(page.select("[data-article-summary-generate]").hasAttr("hidden"));
            assertFalse(response.getBody().contains("sk-fixture-do-not-expose"));
            assertFalse(response.getBody().contains("SQLException"));
            unavailable.saveSummary(link, "Previously saved summary", "fixture");
            var cached = Jsoup.parse(resource.article(link, "").getBody());
            assertEquals("success", cached.select("[data-summary-state]").attr("data-summary-state"));
            assertEquals("Previously saved summary", cached.select("[data-article-summary-text]").text());
        }
    }

    @Test
    void completionDuringStatusReadIsRenderedWithItsSummary() throws Exception {
        var mapper = new ObjectMapper();
        String link = "https://example.com/completion-race";
        try (var completing = new FeedStore(directory.resolve("completing.db"), mapper) {
            @Override public synchronized String loadSummary(String itemLink) throws SQLException {
                String summary = super.loadSummary(itemLink);
                if (summary == null) {
                    saveSummary(itemLink, "Completed while opening article", "fixture");
                    markSummaryFinished(itemLink, Instant.now(), Instant.now(), "fixture", null);
                }
                return summary;
            }
        }; var summarizer = new ArticleSummarizer(null, completing, null, null)) {
            completing.saveItems("/articles", List.of(new FeedItem("Saved", link,
                    "Original content", null, null, List.of())));
            var resource = new ReaderResource(completing, null, renderer, summarizer, null);
            var response = resource.article(link, "/reader/unread");
            assertEquals(200, response.getStatusCode().value());
            var page = Jsoup.parse(response.getBody());
            assertEquals("success", page.select("[data-summary-state]").attr("data-summary-state"));
            assertEquals("Completed while opening article", page.select("[data-article-summary-text]").text());
            assertFalse(completing.isRead(link));
        }
    }

    private void save(String link, String title, String description) throws Exception {
        store.saveItems("/articles", List.of(new FeedItem(title, link, description, null, "作者", List.of("阅读"))));
    }
    private ReaderResource reader(FeedStore value) { return new ReaderResource(value, null, renderer, null, null); }
}
