package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.CacheService;
import app.core.FeedItem;
import app.core.RouteConfigStore;
import app.core.RouteRegistry;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
        assertTrue(fallback.text().contains("AI 整理的内容"));
        assertTrue(fallback.select(".article-prose").text().contains("<script>example()</script>"));
        assertTrue(fallback.select(".article-prose script,.article-prose img").isEmpty());
        var summary = Jsoup.parse(reader(store).article(summarized, "").getBody());
        assertEquals("Only a summary", summary.select(".article-summary p").text());
        assertTrue(summary.select(".article-prose").isEmpty());
        assertTrue(summary.text().contains("暂无已保存内容"));
        var missing = Jsoup.parse(reader(store).article(empty, "").getBody());
        assertTrue(missing.select(".article-summary").isEmpty());
        assertTrue(missing.text().contains("暂无已保存内容"));
    }

    private void save(String link, String title, String description) throws Exception {
        store.saveItems("/articles", List.of(new FeedItem(title, link, description, null, "作者", List.of("阅读"))));
    }
    private ReaderResource reader(FeedStore value) { return new ReaderResource(value, null, renderer, null, null); }
}
