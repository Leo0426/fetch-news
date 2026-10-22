package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.CacheService;
import app.core.FeedItem;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.RouteRegistry;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class UnreadReaderTest {
    @TempDir
    Path directory;
    private FeedStore store;
    private AppRuntime runtime;
    private AdminTemplateRenderer renderer;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        store = new FeedStore(directory.resolve("feed.db"), mapper);
        runtime = new AppRuntime(new CacheService(), new RouteRegistry(List.of(
                new Route("/rss", context -> { throw new AssertionError("Reading must not fetch"); }, "RSS", "自定义"))),
                new RouteConfigStore(directory.resolve("routes.json"), mapper));
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        renderer = new AdminTemplateRenderer(engine, store, runtime, new AppInfo());
    }

    @AfterEach
    void closeStore() throws Exception {
        store.close();
    }

    @Test
    void rendersFiftyCardsAndKeepsLiteralFiltersWhenContinuing() throws Exception {
        String source = "/news+中文?scope=a&b";
        String query = "本地 + & %";
        List<FeedItem> items = articles(query + " <script>alert(1)</script>", 55);
        store.saveItems(source, items);
        for (FeedItem item : items) store.saveBookmark(item.link(), item.title(), source);

        var response = reader(store).unread(source, query, true, null);
        Document page = Jsoup.parse(response.getBody());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(50, page.select(".rd-card[data-link]").size());
        assertEquals("55", page.body().attr("data-queue-total"));
        assertEquals("true", page.body().attr("data-bookmarked-only"));
        assertTrue(page.select(".rd-card script").isEmpty());
        String next = page.select(".unread-pagination a[href*=before]").attr("href");
        assertEquals(source, parameter(next, "source"));
        assertEquals(query, parameter(next, "q"));
        assertEquals("true", parameter(next, "bookmarked"));
        assertNotNull(parameter(next, "before"));

        Document second = Jsoup.parse(reader(store).unread(source, query, true,
                Long.parseLong(parameter(next, "before"))).getBody());
        assertEquals(5, second.select(".rd-card[data-link]").size());
        assertTrue(second.select(".unread-pagination a[href*=before]").isEmpty());
    }

    @Test
    void readingOnePageLeavesTheNextPageUnreadAndKeepsItsCursorValid() throws Exception {
        store.saveItems("/articles", articles("Article", 65));
        ReaderResource resource = reader(store);
        Document first = Jsoup.parse(resource.unread("", "", false, null).getBody());
        List<String> firstLinks = first.select(".rd-card[data-link]").eachAttr("data-link");
        String next = first.select(".unread-pagination a[href*=before]").attr("href");

        assertEquals(200, resource.markAllRead(firstLinks).getStatusCode().value());
        Document second = Jsoup.parse(resource.unread("", "", false,
                Long.parseLong(parameter(next, "before"))).getBody());

        assertEquals(15, second.select(".rd-card[data-link]").size());
        assertEquals("15", second.body().attr("data-queue-total"));
        assertTrue(second.select(".rd-card[data-link]").eachAttr("data-link").stream()
                .noneMatch(firstLinks::contains));
        assertEquals(50, store.loadReadLinks().size());
    }

    @Test
    void invalidCursorAndOversizedQueryHaveRecoverableErrors() {
        ReaderResource resource = reader(store);
        for (var response : List.of(resource.unread("", "", false, -1L),
                resource.unread("", "a".repeat(201), false, null))) {
            Document page = Jsoup.parse(response.getBody());
            assertEquals(400, response.getStatusCode().value());
            assertNotNull(page.selectFirst("[role=alert]"));
            assertEquals("/reader/unread", page.select(".unread-error a").attr("href"));
            assertTrue(page.select(".rd-card").isEmpty());
        }
    }

    @Test
    void unavailableStoreAndFailedQueriesDoNotLookLikeAnEmptyInbox() throws Exception {
        var unavailable = reader(null).unread("", "", false, null);
        assertEquals(503, unavailable.getStatusCode().value());
        assertNotNull(Jsoup.parse(unavailable.getBody()).selectFirst("[role=alert]"));

        store.close();
        var failed = reader(store).unread("", "", false, null);
        assertEquals(500, failed.getStatusCode().value());
        Document page = Jsoup.parse(failed.getBody());
        assertNotNull(page.selectFirst("[role=alert]"));
        assertFalse(page.text().contains("暂时没有待读内容"));
    }

    @Test
    void emptyAndFilteredQueuesOfferDifferentRecoveryActions() throws Exception {
        Document empty = Jsoup.parse(reader(store).unread("", "", false, null).getBody());
        assertTrue(empty.text().contains("暂时没有待读内容"));
        store.saveItems("/articles", articles("Article", 1));
        Document filtered = Jsoup.parse(reader(store).unread("", "missing", false, null).getBody());
        assertTrue(filtered.text().contains("没有符合筛选条件"));
        assertTrue(filtered.select("a[href=/reader/unread]").stream()
                .anyMatch(link -> link.text().contains("查看全部待读")));
    }

    @Test
    void namedRssSourceKeepsItsAliasInTheFilterIncludingAfterItsLastArticleIsRead() throws Exception {
        String source = "/feeds/717368e4-7f43-35cb-836e-5e6b93f266af";
        runtime.routeConfigStore().save(new RouteConfig(source, "/rss", true, 300, 1800,
                60, null, "https://example.test/feed.xml", "阅读闭环测试源"));
        store.saveItems(source, articles("Article", 1));
        ReaderResource resource = reader(store);
        Document page = Jsoup.parse(resource.unread(source, "", false, null).getBody());
        assertEquals("阅读闭环测试源", page.selectFirst(".rd-src").text());
        assertEquals("阅读闭环测试源", page.selectFirst("#unread-source option[selected]").text());
        assertEquals(source, page.selectFirst("#unread-source option[selected]").val());

        resource.markAllRead(List.of("https://example.com/unread/0"));
        Document completed = Jsoup.parse(resource.unread(source, "", false, null).getBody());
        assertEquals("阅读闭环测试源", completed.selectFirst("#unread-source option[selected]").text());
        assertEquals(source, completed.selectFirst("#unread-source option[selected]").val());
    }

    @Test
    void recentReaderGroupsUseConfiguredMountNameAndRegisteredSourceCategory() throws Exception {
        String source = "/my-custom-reading";
        runtime.routeConfigStore().save(new RouteConfig(source, "/rss", true, 300, 1800,
                60, null, "https://example.test/feed.xml", "我的阅读"));
        store.saveItems(source, articles("Article", 1));

        Document page = Jsoup.parse(reader(store).reader(24));
        assertEquals("我的阅读", page.selectFirst(".rd-section-name").text());
        assertEquals("自定义", page.selectFirst(".reader-source-tag").text());
        assertEquals("自定义", page.selectFirst(".rd-section").attr("data-tag"));
    }

    private ReaderResource reader(FeedStore feedStore) {
        return new ReaderResource(feedStore, runtime, renderer, null, null);
    }

    private static List<FeedItem> articles(String title, int count) {
        return IntStream.range(0, count).mapToObj(index -> new FeedItem(title + " " + index,
                "https://example.com/unread/" + index, "Stored body", Instant.EPOCH, null, List.of())).toList();
    }

    private static String parameter(String url, String key) {
        String query = url.substring(url.indexOf('?') + 1);
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals(key)) return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        return null;
    }
}
