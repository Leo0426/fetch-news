package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.CacheService;
import app.core.FeedItem;
import app.core.Route;
import app.core.RouteConfigStore;
import app.core.RouteRegistry;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class BriefingReaderTest {
    @TempDir Path directory;
    private FeedStore store;
    private ReaderResource reader;

    @BeforeEach
    void setup() throws Exception {
        var mapper = new ObjectMapper();
        store = new FeedStore(directory.resolve("feed.db"), mapper);
        var runtime = new AppRuntime(new CacheService(), new RouteRegistry(List.of(
                new Route("/xinhua", ctx -> { throw new AssertionError("Briefings must not fetch feeds"); }, "Fixture", "新闻"),
                new Route("/huanqiu", ctx -> { throw new AssertionError("Briefings must not fetch feeds"); }, "Fixture", "新闻"),
                new Route("/tech", ctx -> { throw new AssertionError("Briefings must not fetch feeds"); }, "Fixture", "科技"))),
                new RouteConfigStore(directory.resolve("routes.json"), mapper));
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var renderer = new AdminTemplateRenderer(engine, store, runtime, new AppInfo());
        reader = new ReaderResource(store, runtime, renderer, null, null);
    }

    @AfterEach
    void close() throws Exception {
        store.close();
    }

    @Test
    void foldsRepeatedTitlesAndKeepsEveryOriginalAccessibleWithoutChangingReadState() throws Exception {
        String first = "https://example.test/first", second = "https://example.test/second?a=1&b=2";
        save("/xinhua", first, "我国成功发射新一代气象观测卫星");
        save("/huanqiu", second, "我国成功发射新一代气象观测卫星");

        var page = Jsoup.parse(reader.briefing(LocalDate.now().toString()));

        assertEquals(1, page.select(".bf-item").size());
        assertEquals(1, page.select(".bf-related").size());
        assertEquals(2, page.select(".bf-item-title").size());
        assertTrue(page.select("a.bf-item-title").stream().anyMatch(a -> first.equals(a.attr("href"))));
        assertTrue(page.select("a.bf-item-title").stream().anyMatch(a -> second.equals(a.attr("href"))));
        assertEquals(2, page.select("[data-article-link]").size());
        assertFalse(page.selectFirst(".bf-related").hasAttr("open"));
        assertEquals("2", page.selectFirst(".bf-total").text());
        assertEquals("1 组 · 2 篇", page.selectFirst(".bf-cat-count").text());
        assertFalse(store.isRead(first));
        assertFalse(store.isRead(second));
        assertEquals(2, store.loadItemsForDate(LocalDate.now()).size());
    }

    @Test
    void repeatedCoverageDoesNotConsumeTheFifteenStoryLimit() throws Exception {
        for (int i = 0; i < 20; i++) {
            save("/xinhua", "https://example.test/repeat-" + i, "我国成功发射新一代气象观测卫星");
        }

        var page = Jsoup.parse(reader.briefing(LocalDate.now().toString()));

        assertEquals(1, page.select(".bf-item").size());
        assertEquals(20, page.select("a.bf-item-title").size());
        assertEquals(19, page.select(".bf-related-item").size());
        assertTrue(page.select(".bf-more").isEmpty());
        assertEquals("1 组 · 20 篇", page.selectFirst(".bf-cat-count").text());
    }

    @Test
    void keepsCategoryBoundariesEvenWhenTitlesMatch() throws Exception {
        save("/xinhua", "https://example.test/news", "我国成功发射新一代气象观测卫星");
        save("/tech", "https://example.test/tech", "我国成功发射新一代气象观测卫星");

        var page = Jsoup.parse(reader.briefing(LocalDate.now().toString()));

        assertEquals(2, page.select(".bf-section").size());
        assertEquals(2, page.select(".bf-item").size());
        assertTrue(page.select(".bf-related, .bf-folding-note").isEmpty());
    }

    @Test
    void escapesTitlesAndPreservesEachArticlesOwnSummaryAndState() throws Exception {
        String first = "https://example.test/first", second = "https://example.test/second";
        String title = "<script>alert('same title')</script> 我国成功发射气象卫星";
        save("/xinhua", first, title);
        save("/huanqiu", second, title);
        store.saveSummary(first, "First summary <img src=x onerror=bad()>", "fixture");
        store.saveSummary(second, "Second summary <script>bad()</script>", "fixture");
        store.markRead(first);
        store.saveBookmark(second, title, "/huanqiu");

        var page = Jsoup.parse(reader.briefing(LocalDate.now().toString()));

        assertEquals(1, page.select(".bf-item").size());
        assertTrue(page.select(".bf-items script, .bf-items img").isEmpty());
        assertEquals(List.of(title, title), page.select("a.bf-item-title").eachText());
        assertTrue(page.select(".bf-item-summary").eachText().contains("First summary <img src=x onerror=bad()>"));
        assertTrue(page.select(".bf-item-summary").eachText().contains("Second summary <script>bad()</script>"));
        assertTrue(store.isRead(first));
        assertFalse(store.isRead(second));
        assertTrue(store.isBookmarked(second));
    }

    @Test
    void dateNavigationAndEmptyDatesRemainAvailable() throws Exception {
        save("/xinhua", "https://example.test/today", "我国成功发射新一代气象观测卫星");

        var page = Jsoup.parse(reader.briefing(LocalDate.now().minusDays(2).toString()));

        assertEquals(1, page.select(".bf-empty").size());
        assertTrue(page.select(".bf-item, .bf-folding-note").isEmpty());
        assertEquals("0", page.selectFirst(".bf-total").text());
        assertTrue(page.select("a[href='/reader/briefing?date=" + LocalDate.now().minusDays(3) + "']").size() > 0);
    }

    @Test
    void skipsUntitledArticlesWithoutCountingThemAsFoldedCoverage() throws Exception {
        save("/xinhua", "https://example.test/valid", "我国成功发射新一代气象观测卫星");
        save("/xinhua", "https://example.test/untitled", null);
        save("/xinhua", "https://example.test/blank", " ");

        var page = Jsoup.parse(reader.briefing(LocalDate.now().toString()));

        assertEquals(1, page.select(".bf-item").size());
        assertEquals("1", page.selectFirst(".bf-total").text());
        assertTrue(page.select(".bf-folding-note").isEmpty());
    }

    private void save(String route, String link, String title) throws Exception {
        store.saveItems(route, List.of(new FeedItem(title, link, "Saved original body", null, null, List.of())));
    }
}
