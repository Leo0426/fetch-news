package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.CacheService;
import app.core.Feed;
import app.core.FeedItem;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.RouteException;
import app.core.RouteRegistry;
import app.store.FeedStore;
import app.store.FeedRefreshState;
import app.routes.GenericRssRoute;
import app.subscriptions.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubscriptionServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void subscribingPersistsRecommendedScheduleAndFetchesOnceWithoutTogglingOnRepeat() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Route route = new Route("/science", context -> {
            calls.incrementAndGet();
            return new Feed("Science", "https://example.test", "Research", List.of(
                    new FeedItem("Research article", "https://example.test/article", "Research", null, null, List.of())));
        }, "Science · Research", "科学", "RSS", "Official feed", 180);
        var configs = new RouteConfigStore(directory.resolve("routes.json"), mapper);
        var runtime = new AppRuntime(new CacheService(), new RouteRegistry(List.of(route)), configs);
        try (var store = new FeedStore(directory.resolve("feeds.db"), mapper)) {
            var scheduler = new FetchScheduler(runtime, store, null, null, null);
            var service = new SubscriptionService(runtime, store, scheduler);
            try {
                service.start("/science", null);
                assertTrue(configs.get("/science").enabled());
                assertEquals(180, configs.get("/science").scheduleMinutes());
                service.start("/science", null);
            } finally {
                scheduler.shutdown();
            }
            assertEquals(1, calls.get());
            assertEquals(1, store.countItems("/science"));
            assertTrue(new RouteConfigStore(directory.resolve("routes.json"), mapper).get("/science").hasSchedule());
            assertEquals("success", service.source("/science").status().kind());
            assertEquals(1, store.loadUnreadPage("/science", "", false, null, 50).totalCount());
        }
    }

    @Test void browsingAndFilteringDoesNotSubscribeOrFetchAndParameterizedTemplatesStayOut() {
        var registry = new RouteRegistry(List.of(
                new Route("/science", context -> { throw new AssertionError("Browsing must not fetch"); },
                        "Science · Research", "科学", "RSS", "Feed", 180),
                new Route("/news/:topic", context -> { throw new AssertionError("Browsing must not fetch"); },
                        "News", "新闻")));
        var configs = new RouteConfigStore(directory.resolve("routes.json"), mapper);
        var runtime = new AppRuntime(new CacheService(), registry, configs);
        var scheduler = new FetchScheduler(runtime, null, null, null, null);
        try {
            var service = new SubscriptionService(runtime, null, scheduler);
            assertEquals(1, service.page(null, null, null).totalCount());
            assertEquals("Science", service.page("research", "科学", "all").sources().getFirst().name());
            assertTrue(service.page("absent", null, null).sources().isEmpty());
            assertTrue(service.page(null, null, "subscribed").sources().isEmpty());
            assertThrows(RouteException.class, () -> service.start("/news/:topic", null));
            assertThrows(RouteException.class, () -> service.start("/unconfigured", null));
            assertThrows(IllegalArgumentException.class, () -> service.start("/science", 0));
            assertFalse(Files.exists(directory.resolve("routes.json")));
        } finally { scheduler.shutdown(); }
    }

    @Test void legacyEnabledSourceGetsChosenIntervalAndPausingKeepsArticlesAndSettings() throws Exception {
        Route route = new Route("/science", context -> new Feed("Science", "https://example.test", "Research", List.of(
                new FeedItem("Research", "https://example.test/article", "Text", null, null, List.of()))),
                "Science", "科学", "RSS", "Feed", 180);
        var configs = new RouteConfigStore(directory.resolve("routes.json"), mapper);
        configs.save(new RouteConfig("/science", "/science", true, 900, 3600, 0, null, null, "我的科学"));
        var runtime = new AppRuntime(new CacheService(), new RouteRegistry(List.of(route)), configs);
        try (var store = new FeedStore(directory.resolve("feeds.db"), mapper)) {
            var scheduler = new FetchScheduler(runtime, store, null, null, null);
            var service = new SubscriptionService(runtime, store, scheduler);
            try {
                assertEquals(1, service.page(null, null, "attention").sources().size());
                service.start("/science", 360);
            } finally { scheduler.shutdown(); }
            assertEquals(360, configs.get("/science").scheduleMinutes());
            service.pause("/science");
            service.pause("/science");
            assertFalse(configs.get("/science").enabled());
            assertEquals(360, configs.get("/science").scheduleMinutes());
            assertEquals(900, configs.get("/science").routeCacheTtlSeconds());
            assertEquals(3600, configs.get("/science").detailCacheTtlSeconds());
            assertEquals("我的科学", service.source("/science").name());
            assertEquals(1, store.countItems("/science"));
            assertThrows(IllegalArgumentException.class, () -> service.refresh("/science"));
        }
    }

    @Test void resumingMountKeepsCronAndSourceAndReloadReadsLastSavedResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Route route = new Route("/news/:topic", context -> {
            assertEquals("science", context.pathParam("topic"));
            calls.incrementAndGet();
            return new Feed("News", "https://example.test", "News", List.of(
                    new FeedItem("News", "https://example.test/story", "Text", null, null, List.of())));
        }, "News", "新闻");
        var configs = new RouteConfigStore(directory.resolve("routes.json"), mapper);
        configs.save(new RouteConfig("/news/science", "/news/:topic", false, 800, 2400, 120,
                "0 0 8 * * *", "https://example.test/source.xml", "科学新闻"));
        var runtime = new AppRuntime(new CacheService(), new RouteRegistry(List.of(route)), configs);
        try (var store = new FeedStore(directory.resolve("feeds.db"), mapper)) {
            var scheduler = new FetchScheduler(runtime, store, null, null, null);
            var service = new SubscriptionService(runtime, store, scheduler);
            try { service.start("/news/science", 1); }
            finally { scheduler.shutdown(); }
            assertEquals(1, calls.get());
            var saved = configs.get("/news/science");
            assertTrue(saved.enabled());
            assertEquals(120, saved.scheduleMinutes());
            assertEquals("0 0 8 * * *", saved.scheduleCron());
            assertEquals("/news/:topic", saved.sourcePath());
            assertEquals("https://example.test/source.xml", saved.feedUrl());
            var restarted = new FetchScheduler(runtime, store, null, null, null);
            try {
                var restored = new SubscriptionService(runtime, store, restarted).source(saved.path());
                assertEquals("success", restored.status().kind());
                assertEquals(1, restored.articleCount());
                assertEquals("/reader/unread?source=%2Fnews%2Fscience", restored.readerUrl());
                store.logFetch(saved.path(), Instant.now().minusSeconds(10), 1, 0, 0, "Source failed");
                assertEquals("error", new SubscriptionService(runtime, store, restarted).source(saved.path()).status().kind());
            } finally { restarted.shutdown(); }
        }
    }

    @Test void retryWaitSurvivesReloadAndChangingFeedDoesNotShowOldSuccess() throws Exception {
        String oldUrl = "https://example.test/feed.xml";
        var configs = new RouteConfigStore(directory.resolve("routes.json"), mapper);
        configs.save(new RouteConfig("/local", "/rss", false, 300, 1800, 0, null, oldUrl, "Local"));
        try (var store = new FeedStore(directory.resolve("feeds.db"), mapper)) {
            Route route = new Route("/rss", new GenericRssRoute(url -> {
                throw new AssertionError("Retry wait must prevent upstream requests");
            }, store), "RSS", "自定义");
            var runtime = new AppRuntime(new CacheService(), new RouteRegistry(List.of(route)), configs);
            var scheduler = new FetchScheduler(runtime, store, null, null, null);
            Instant now = Instant.now();
            store.logFetch("/local", now.minusSeconds(60), 1, 1, 1, null);
            store.saveFeedRefreshState(new FeedRefreshState("/local", oldUrl, now, now.minusSeconds(60), null,
                    now.plusSeconds(3600), 1, 429, 0, 1, "Rate limited"));
            try { new SubscriptionService(runtime, store, scheduler).start("/local", null); }
            finally { scheduler.shutdown(); }
            var restarted = new FetchScheduler(runtime, store, null, null, null);
            try {
                var service = new SubscriptionService(runtime, store, restarted);
                assertEquals("waiting", service.source("/local").status().kind());
                assertTrue(service.source("/local").status().detail().contains("自动重试"));
                configs.save(new RouteConfig("/local", "/rss", true, 300, 1800, 60, null,
                        "https://example.test/new.xml", "Local"));
                assertEquals("idle", service.source("/local").status().kind());
                assertTrue(service.source("/local").status().title().contains("当前地址"));
                store.saveFeedRefreshState(new FeedRefreshState("/local", oldUrl, now.minusSeconds(20), null, null,
                        now.minusSeconds(1), 1, 429, 0, 1, "Old address failed"));
                store.logFetch("/local", now.plusSeconds(1), 1, 2, 2, null);
                configs.save(new RouteConfig("/local", "/rss", true, 300, 1800, 60, null, oldUrl, "Local"));
                assertEquals("error", service.source("/local").status().kind());
                assertEquals("Old address failed", service.source("/local").status().detail());
                assertEquals(2, store.getRecentLogs("/local", 10).size());
            } finally { restarted.shutdown(); }
        }
    }
}
