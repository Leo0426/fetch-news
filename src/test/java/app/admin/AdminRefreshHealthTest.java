package app.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.AppRuntime;
import app.TestPages;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.RouteContext;
import app.core.Topic;
import app.routes.GenericRssRoute;
import app.store.FeedRefreshState;
import app.store.FeedStore;
import app.store.RouteHealth;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminRefreshHealthTest {
    private static final String FEED_URL = "https://example.com/feed.xml?token=private-token";
    private static final String FIXED_FEED_URL = "https://example.com/fixed.xml";
    private static final Instant CHECKED_AT = Instant.parse("2026-10-03T10:15:30Z");

    @TempDir
    Path tempDir;

    private AppRuntime runtime;
    private AdminTemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        runtime = TestPages.runtime(new RouteConfigStore(tempDir.resolve("routes.json"), new ObjectMapper()), new Route("/rss", new GenericRssRoute(url -> {
                    throw new AssertionError("Health display must not fetch upstream feeds");
                }), "Generic RSS", Topic.GENERAL),
                new Route("/fixed/news", new GenericRssRoute(url -> {
                    throw new AssertionError("Health display must not fetch upstream feeds");
                }) {
                    @Override public String feedUrl(RouteContext context) { return FIXED_FEED_URL; }
                }, "Fixed RSS", Topic.GENERAL),
                new Route("/hn/top", context -> {
                    throw new AssertionError("Health display must not fetch upstream feeds");
                }, "Native source", Topic.GENERAL));
        var engine = TestPages.engine();
        renderer = new AdminTemplateRenderer(engine, null, runtime, new AppInfo());
    }

    @Test
    void unchangedFeedShowsCheckSuccessChangeTimesAndTransferDetails() {
        FeedRefreshState state = new FeedRefreshState(
                "/stable", FEED_URL, CHECKED_AT, CHECKED_AT, Instant.parse("2026-10-02T08:00:00Z"),
                null, 0, 304, 0, 35, null);

        String html = renderer.healthSection(List.of(rssRoute("/stable", FEED_URL)), Map.of(),
                List.of(state));
        Document document = Jsoup.parse(html);
        String status = document.select("#rss-refresh-status").text();

        assertTrue(status.contains("内容未变化"));
        assertTrue(status.contains("2026-10-03T10:15:30Z"));
        assertTrue(status.contains("2026-10-02T08:00:00Z"));
        assertTrue(status.contains("UTC"));
        assertTrue(status.contains("304"));
        assertTrue(status.contains("0 B"));
        assertTrue(status.contains("35 ms"));
        assertEquals("2026-10-03T10:15:30Z", document.select("#rss-refresh-status tbody td").get(3).text());
        assertFalse(html.contains("private-token"));
        assertFalse(html.contains("example.com"));
    }

    @Test
    void futureRetryShowsWaitingAndEscapesErrorsWithoutLeakingFeedUrl() {
        String error = "<script>alert('error')</script> HTTP 429: " + FEED_URL;
        FeedRefreshState state = new FeedRefreshState(
                "/waiting", FEED_URL, CHECKED_AT, null, null, Instant.parse("2999-01-01T00:00:00Z"),
                4, 429, 128, 90, error);

        String html = renderer.healthSection(List.of(rssRoute("/waiting", FEED_URL)), Map.of(),
                List.of(state));
        Document document = Jsoup.parse(html);
        String status = document.select("#rss-refresh-status").text();

        assertTrue(status.contains("等待重试"));
        assertTrue(status.contains("2999-01-01T00:00:00Z"));
        assertTrue(status.contains("<script>alert('error')</script> HTTP 429:"));
        assertTrue(status.contains("128 B"));
        assertTrue(status.contains("90 ms"));
        assertEquals("4", document.select("#rss-refresh-status tbody td").get(8).text());
        assertTrue(document.select("script").isEmpty());
        assertFalse(html.contains("private-token"));
        assertFalse(html.contains("example.com"));
    }

    @Test
    void onlyCurrentRssSourceStatesAreDisplayed() {
        List<RouteConfig> routes = List.of(
                rssRoute("/current", FEED_URL),
                rssRoute("/changed", "https://other.example.com/feed.xml"),
                new RouteConfig("/native", "/hn/top", true, 300, 1800));
        List<FeedRefreshState> states = List.of(successfulState("/current"), successfulState("/changed"),
                successfulState("/deleted"), successfulState("/native"));

        Document document = Jsoup.parse(renderer.healthSection(routes, Map.of(), states));
        String status = document.select("#rss-refresh-status").text();

        assertEquals(1, document.select("#rss-refresh-status tbody tr").size());
        assertTrue(status.contains("/current"));
        assertTrue(status.contains("刷新成功"));
        assertFalse(status.contains("/changed"));
        assertFalse(status.contains("/deleted"));
        assertFalse(status.contains("/native"));
    }

    @Test
    void fixedSourcesAndTheirAliasesShowOnlyTheCurrentRouteAndUpstreamUrl() {
        List<RouteConfig> routes = List.of(
                new RouteConfig("/fixed/news", "/fixed/news", true, 300, 1800,
                        0, null, FEED_URL, null),
                new RouteConfig("/fixed-copy", "/fixed/news", true, 300, 1800));
        List<FeedRefreshState> states = List.of(
                new FeedRefreshState("/fixed/news", FIXED_FEED_URL, CHECKED_AT, CHECKED_AT,
                        CHECKED_AT, null, 0, 304, 0, 25, null),
                new FeedRefreshState("/fixed-copy", FIXED_FEED_URL, CHECKED_AT, null, null,
                        Instant.parse("2999-01-01T00:00:00Z"), 1, 429, 0, 10, "Rate limited"),
                new FeedRefreshState("/fixed/news", FEED_URL, CHECKED_AT.plusSeconds(60),
                        CHECKED_AT.plusSeconds(60), CHECKED_AT.plusSeconds(60), null, 0, 200, 100, 20, null),
                new FeedRefreshState("/deleted", FIXED_FEED_URL, CHECKED_AT, CHECKED_AT,
                        CHECKED_AT, null, 0, 200, 100, 20, null));

        Document document = Jsoup.parse(renderer.healthSection(routes, Map.of(), states));
        var rows = document.select("#rss-refresh-status tbody tr");

        assertEquals(2, rows.size());
        assertTrue(rows.get(0).text().contains("/fixed/news"));
        assertTrue(rows.get(0).text().contains("内容未变化"));
        assertTrue(rows.get(1).text().contains("/fixed-copy"));
        assertTrue(rows.get(1).text().contains("等待重试"));
        assertFalse(document.select("#rss-refresh-status").text().contains("刷新成功"));
        assertFalse(document.select("#rss-refresh-status").text().contains("/deleted"));
    }

    @Test
    void expiredRetryShowsFailureAndMissingValues() {
        FeedRefreshState state = new FeedRefreshState(
                "/failed", FEED_URL, CHECKED_AT, null, null, Instant.parse("2000-01-01T00:00:00Z"),
                1, 0, 0, 15, "Connection reset");

        String status = Jsoup.parse(renderer.healthSection(List.of(rssRoute("/failed", FEED_URL)),
                Map.of(), List.of(state))).select("#rss-refresh-status").text();

        assertTrue(status.contains("刷新失败"));
        assertTrue(status.contains("Connection reset"));
        assertTrue(status.contains("—"));
        assertFalse(status.contains("等待重试"));
    }

    @Test
    void unsuccessfulNotModifiedResponseShowsFailureAfterRetryExpires() {
        FeedRefreshState state = new FeedRefreshState(
                "/cold", FEED_URL, CHECKED_AT, null, null, Instant.parse("2000-01-01T00:00:00Z"),
                1, 304, 0, 15, "No valid cached feed for HTTP 304");

        String status = Jsoup.parse(renderer.healthSection(List.of(rssRoute("/cold", FEED_URL)),
                Map.of(), List.of(state))).select("#rss-refresh-status").text();

        assertTrue(status.contains("刷新失败"));
        assertTrue(status.contains("No valid cached feed for HTTP 304"));
        assertFalse(status.contains("内容未变化"));
    }

    @Test
    void healthResourceLoadsPersistedRefreshState() throws Exception {
        runtime.routeConfigStore().save(rssRoute("/persisted", FEED_URL));
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            store.fetchState().saveFeedRefreshState(successfulState("/persisted"));
            AdminResource resource = new AdminResource(runtime, new ObjectMapper(), renderer, store, null);

            Document document = Jsoup.parse(resource.health());

            assertEquals(1, document.select("#rss-refresh-status tbody tr").size());
            assertTrue(document.select("#rss-refresh-status").text().contains("/persisted"));
        }
    }

    @Test
    void healthResourceUsesCurrentSourceWhenOldSourceCompletesLater() throws Exception {
        String nextUrl = "https://other.example.com/feed.xml";
        runtime.routeConfigStore().save(rssRoute("/switched", nextUrl));
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            store.fetchState().saveFeedRefreshState(new FeedRefreshState(
                    "/switched", nextUrl, CHECKED_AT, null, null, Instant.parse("2999-01-01T00:00:00Z"),
                    3, 429, 0, 30, "Too many requests"));
            store.fetchState().saveFeedRefreshState(new FeedRefreshState(
                    "/switched", FEED_URL, CHECKED_AT.plusSeconds(10), CHECKED_AT.plusSeconds(10),
                    CHECKED_AT.plusSeconds(10), null, 0, 200, 100, 10_000, null));
            AdminResource resource = new AdminResource(runtime, new ObjectMapper(), renderer, store, null);

            Document document = Jsoup.parse(resource.health());

            assertEquals(1, document.select("#rss-refresh-status tbody tr").size());
            assertTrue(document.select("#rss-refresh-status").text().contains("等待重试"));
            assertTrue(document.select("#rss-refresh-status").text().contains("Too many requests"));
            assertFalse(document.select("#rss-refresh-status").text().contains("刷新成功"));
        }
    }

    @Test
    void legacyRendererEntryPointStillShowsRouteHealth() {
        RouteHealth health = new RouteHealth("/legacy", CHECKED_AT, "ok", null, 1, 1, 50);

        Document document = Jsoup.parse(renderer.healthSection(List.of(), Map.of("/legacy", health)));

        assertTrue(document.text().contains("/legacy"));
        assertNotNull(document.selectFirst("#health-view"));
        assertTrue(document.select("#rss-refresh-status tbody tr").isEmpty());
    }

    private static RouteConfig rssRoute(String path, String feedUrl) {
        return new RouteConfig(path, "/rss", true, 300, 1800, 0, null, feedUrl, null);
    }

    private static FeedRefreshState successfulState(String path) {
        return new FeedRefreshState(path, FEED_URL, CHECKED_AT, CHECKED_AT, CHECKED_AT,
                null, 0, 200, 256, 40, null);
    }
}
