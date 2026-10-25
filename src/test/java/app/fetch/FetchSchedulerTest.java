package app.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.AppRuntime;
import app.TestPages;
import app.ai.AiConfig;
import app.ai.AiConfigStore;
import app.ai.ArticleExtractor;
import app.ai.ArticleSummarizer;
import app.ai.OllamaClient;
import app.core.Feed;
import app.core.FeedItem;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.RouteError;
import app.core.RouteException;
import app.routes.GenericRssRoute;
import app.store.FeedRefreshState;
import app.store.FeedStore;
import app.subscriptions.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FetchSchedulerTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesQueuedProgressAndCoalescesRequestsBeforeExecution() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AppRuntime runtime = runtime(new Route("/queued-progress", context -> {
            calls.incrementAndGet();
            return feed();
        }));
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            occupied.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        });
        try (FeedStore store = new FeedStore(tempDir.resolve("queued-progress.db"), objectMapper)) {
            FetchScheduler scheduler = new FetchScheduler(runtime, store, null, null, null, executor);
            try {
                assertTrue(occupied.await(2, TimeUnit.SECONDS));
                scheduler.triggerFetch("/queued-progress");
                FetchProgress running = scheduler.progress("/queued-progress");
                assertNotNull(running, "accepted work must be visible before its executor starts");
                assertTrue(running.running());
                assertNull(running.finishedAt());
                scheduler.triggerFetch("/queued-progress");
                scheduler.tick();
                assertEquals(running, scheduler.progress("/queued-progress"));
                assertEquals(0, calls.get());
            } finally {
                release.countDown();
                scheduler.shutdown();
            }
            FetchProgress completed = scheduler.progress("/queued-progress");
            assertEquals(FetchProgress.State.SUCCEEDED, completed.state());
            assertFalse(completed.running());
            assertNotNull(completed.finishedAt());
            assertEquals(1, completed.itemCount());
            assertEquals(1, completed.newItemCount());
            assertEquals(store.fetchState().latestFetchLog("/queued-progress").id(), completed.logId());
            assertEquals(1, calls.get());
            assertEquals(1, store.fetchState().getRecentLogs("/queued-progress", 10).size());
        }
    }

    @Test
    void reportsPersistenceFailureEvenWhenTheUpstreamFeedWasReturned() throws Exception {
        String path = "/storage-failure";
        Path database = tempDir.resolve("storage-failure.db");
        AppRuntime runtime = runtime(new Route(path, context -> feed()));
        try (FeedStore store = new FeedStore(database, objectMapper)) {
            executeSql(database, """
                    CREATE TRIGGER reject_items BEFORE INSERT ON feed_items
                    BEGIN SELECT RAISE(ABORT, 'test storage unavailable'); END
                    """);
            FetchScheduler scheduler = scheduler(runtime, store);
            try {
                scheduler.fetchNow(runtime.routeConfigStore().get(path));
                FetchProgress progress = scheduler.progress(path);
                assertEquals(FetchProgress.State.FAILED, progress.state());
                assertFalse(progress.running());
                assertTrue(progress.error().contains("persistence failed"));
                assertEquals(store.fetchState().latestFetchLog(path).id(), progress.logId());
                assertTrue(store.loadItems(path).isEmpty());
            } finally {
                scheduler.shutdown();
            }
        }
    }

    @Test
    void preservesTheLatestFailureLogWhenAnUpstreamRequestThrows() throws Exception {
        String path = "/upstream-failure";
        AppRuntime runtime = runtime(new Route(path, context -> {
            throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "temporary outage");
        }));
        try (FeedStore store = new FeedStore(tempDir.resolve("upstream-failure.db"), objectMapper)) {
            store.fetchState().logFetch(path, Instant.now().plusSeconds(3600), 1, 3, 3, null);
            long previousLogId = store.fetchState().latestFetchLog(path).id();
            FetchScheduler scheduler = scheduler(runtime, store);
            try {
                scheduler.fetchNow(runtime.routeConfigStore().get(path));
                FetchProgress progress = scheduler.progress(path);
                assertEquals(FetchProgress.State.FAILED, progress.state());
                assertEquals("temporary outage", progress.error());
                assertEquals(store.fetchState().latestFetchLog(path).id(), progress.logId());
                assertTrue(progress.logId() > previousLogId);
            } finally {
                scheduler.shutdown();
            }
        }
    }

    @Test
    void doesNotReuseAnOldSuccessWhenTheCurrentFetchCannotWriteItsLog() throws Exception {
        String path = "/missing-log";
        Path database = tempDir.resolve("missing-log.db");
        AppRuntime runtime = runtime(new Route(path, context -> feed()));
        try (FeedStore store = new FeedStore(database, objectMapper)) {
            store.fetchState().logFetch(path, Instant.now().plusSeconds(3600), 1, 99, 99, null);
            long previousLogId = store.fetchState().latestFetchLog(path).id();
            executeSql(database, """
                    CREATE TRIGGER reject_fetch_log BEFORE INSERT ON fetch_log
                    BEGIN SELECT RAISE(ABORT, 'test log unavailable'); END
                    """);
            FetchScheduler scheduler = scheduler(runtime, store);
            try {
                scheduler.fetchNow(runtime.routeConfigStore().get(path));
                FetchProgress progress = scheduler.progress(path);
                assertEquals(FetchProgress.State.UNCONFIRMED, progress.state());
                assertFalse(progress.running());
                assertNotNull(progress.finishedAt());
                assertEquals(1, store.loadItems(path).size());
                assertEquals(0, progress.newItemCount());
                assertEquals(previousLogId, progress.logId(), "unconfirmed results retain the old log watermark");
                assertEquals("unknown", new SubscriptionService(runtime, store, scheduler).source(path).status().kind());
            } finally {
                scheduler.shutdown();
            }
        }
    }

    @Test
    void confirmsTheNewLogEvenWhenOldLogsHaveLaterTimestamps() throws Exception {
        String path = "/log-order";
        AppRuntime runtime = runtime(new Route(path, context -> feed()));
        try (FeedStore store = new FeedStore(tempDir.resolve("log-order.db"), objectMapper)) {
            store.fetchState().logFetch(path, Instant.now().plusSeconds(3600), 1, 99, 99, "old failure");
            FetchScheduler scheduler = scheduler(runtime, store);
            try {
                scheduler.fetchNow(runtime.routeConfigStore().get(path));
                FetchProgress progress = scheduler.progress(path);
                assertEquals(FetchProgress.State.SUCCEEDED, progress.state());
                assertEquals(1, progress.itemCount());
                assertEquals(1, progress.newItemCount());
                assertEquals(store.fetchState().latestFetchLog(path).id(), progress.logId());
                assertNull(progress.error());
            } finally {
                scheduler.shutdown();
            }
        }
    }

    @Test
    void exposesDeferredRetryWithoutCallingUpstreamOrInventingAFetchLog() throws Exception {
        String path = "/deferred";
        String url = "https://example.com/deferred.xml";
        Instant retryAt = Instant.now().plusSeconds(3600);
        try (FeedStore store = new FeedStore(tempDir.resolve("deferred.db"), objectMapper)) {
            GenericRssRoute route = new GenericRssRoute(requestedUrl -> {
                throw new AssertionError("A deferred refresh must not call upstream");
            }, store);
            AppRuntime runtime = runtime(new Route(path, route));
            RouteConfig config = new RouteConfig(path, path, true, 300, 1800, 60, null, url, null);
            runtime.routeConfigStore().save(config);
            store.fetchState().saveFeedRefreshState(new FeedRefreshState(path, url, Instant.now(), null, null,
                    retryAt, 1, 429, 0, 10, "rate limited"));
            FetchScheduler scheduler = scheduler(runtime, store);
            try {
                scheduler.triggerFetch(path);
            } finally {
                scheduler.shutdown();
            }
            FetchProgress progress = scheduler.progress(path);
            assertEquals(FetchProgress.State.DEFERRED, progress.state());
            assertFalse(progress.running());
            assertEquals(retryAt, progress.retryAt());
            assertNotNull(progress.finishedAt());
            assertEquals(0, progress.logId());
            assertTrue(store.fetchState().getRecentLogs(path, 1).isEmpty());
        }
    }

    @Test
    void keepsProgressWhenPausedButHidesResultsForADifferentSource() throws Exception {
        String path = "/progress-config";
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AppRuntime runtime = runtime(new Route(path, context -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return feed();
        }));
        RouteConfig paused = new RouteConfig(path, path, false, 30, 1800, 90, null, null, "Renamed");
        FetchScheduler scheduler = scheduler(runtime, null);
        try {
            assertFalse(scheduler.busyWithDifferentSource(path));
            scheduler.triggerFetch(path);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            FetchProgress running = scheduler.progress(path);
            runtime.routeConfigStore().save(paused);
            assertEquals(running, scheduler.progress(path), "pause, interval, alias and route cache do not change the source");
            assertFalse(scheduler.busyWithDifferentSource(path));

            List<RouteConfig> changedSources = List.of(
                    new RouteConfig(path, "/other", false, 30, 1800, 90, null, null, null),
                    new RouteConfig(path, path, false, 30, 1800, 90, null, "https://example.com/other.xml", null),
                    new RouteConfig(path, path, false, 30, 900, 90, null, null, null));
            for (RouteConfig changed : changedSources) {
                runtime.routeConfigStore().save(changed);
                assertNull(scheduler.progress(path));
                assertTrue(scheduler.busyWithDifferentSource(path));
            }
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
        assertNull(scheduler.progress(path), "completion from the old source must remain hidden");
        assertFalse(scheduler.busyWithDifferentSource(path), "a completed old request no longer blocks the new source");
        runtime.routeConfigStore().save(paused);
        assertEquals(FetchProgress.State.UNCONFIRMED, scheduler.progress(path).state());
        assertFalse(scheduler.progress(path).running());
    }

    @Test
    void autoSummaryRouteEnqueuesNewItemsWithoutWaitingForInference() throws Exception {
        assertEquals("queued", fetchAndReadSummaryState(true));
    }

    @Test
    void routesDoNotEnqueueSummariesUnlessAutoSummaryIsOn() throws Exception {
        assertEquals("none", fetchAndReadSummaryState(false));
    }

    private String fetchAndReadSummaryState(boolean autoSummary) throws Exception {
        AppRuntime runtime = runtime(new Route("/queued", context -> feed()));
        AiConfigStore aiConfig = new AiConfigStore(tempDir.resolve("ai.json"), objectMapper);
        aiConfig.save(new AiConfig("http://127.0.0.1:1", "fixture", true));
        OllamaClient model = new OllamaClient(aiConfig, objectMapper) {
            @Override public Generation generateWithMetadata(String prompt) {
                throw new AssertionError("Fetching must only enqueue summaries");
            }
        };
        RouteConfig base = runtime.routeConfigStore().get("/queued");
        RouteConfig config = new RouteConfig(base.path(), base.sourcePath(), base.enabled(),
                base.routeCacheTtlSeconds(), base.detailCacheTtlSeconds(), base.scheduleMinutes(),
                base.scheduleCron(), base.feedUrl(), base.alias(), autoSummary);
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), objectMapper);
             ArticleSummarizer summaries = new ArticleSummarizer(model, store,
                     new ArticleExtractor(model, store, url -> { throw new AssertionError("No article fetch expected"); }), aiConfig)) {
            FetchScheduler scheduler = new FetchScheduler(runtime, store, summaries, aiConfig, null);
            try {
                scheduler.fetchNow(config);
                assertEquals(1, store.loadItems("/queued").size());
                assertEquals("ok", store.fetchState().getRecentLogs("/queued", 1).getFirst().status());
                return summaries.progress(feed().items().getFirst().link()).state();
            } finally {
                scheduler.shutdown();
            }
        }
    }

    @Test
    void coalescesManualScheduledAndDirectFetchesForTheSamePath() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch duplicateStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AppRuntime runtime = runtime(new Route("/slow", context -> {
            if (calls.incrementAndGet() > 1) duplicateStarted.countDown();
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS), "blocked fetch must be released");
            return feed();
        }));

        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), objectMapper);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            FetchScheduler scheduler = scheduler(runtime, store);
            try {
                scheduler.triggerFetch("/slow");
                assertTrue(started.await(2, TimeUnit.SECONDS));

                scheduler.triggerFetch("/slow");
                scheduler.tick();
                CountDownLatch directCalling = new CountDownLatch(1);
                var direct = executor.submit(() -> {
                    directCalling.countDown();
                    scheduler.fetchNow(runtime.routeConfigStore().get("/slow"));
                });
                assertTrue(directCalling.await(2, TimeUnit.SECONDS));
                duplicateStarted.await(150, TimeUnit.MILLISECONDS);

                assertEquals(1, calls.get());
                release.countDown();
                direct.get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                scheduler.shutdown();
            }
        }
    }

    @Test
    void fetchesAnotherPathWhileOnePathIsBlocked() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch fastStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AppRuntime runtime = runtime(
                new Route("/slow", context -> {
                    slowStarted.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return feed();
                }),
                new Route("/fast", context -> {
                    fastStarted.countDown();
                    return feed();
                }));
        FetchScheduler scheduler = scheduler(runtime, null);
        try {
            scheduler.triggerFetch("/slow");
            assertTrue(slowStarted.await(2, TimeUnit.SECONDS));
            scheduler.triggerFetch("/fast");

            assertTrue(fastStarted.await(2, TimeUnit.SECONDS), "unrelated routes must not wait for a slow route");
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
    }

    @Test
    void allowsAnotherFetchAfterFailure() {
        AtomicInteger calls = new AtomicInteger();
        AppRuntime runtime = runtime(new Route("/retry", context -> {
            if (calls.incrementAndGet() == 1) {
                throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "temporary outage");
            }
            return feed();
        }));
        FetchScheduler scheduler = scheduler(runtime, null);
        try {
            RouteConfig config = runtime.routeConfigStore().get("/retry");
            scheduler.fetchNow(config);
            assertEquals(FetchProgress.State.FAILED, scheduler.progress("/retry").state());
            assertEquals("temporary outage", scheduler.progress("/retry").error());
            assertFalse(scheduler.progress("/retry").running());
            scheduler.fetchNow(config);

            assertEquals(2, calls.get());
            assertEquals(FetchProgress.State.UNCONFIRMED, scheduler.progress("/retry").state());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void allowsAnotherFetchAfterSuccess() {
        AtomicInteger calls = new AtomicInteger();
        AppRuntime runtime = runtime(new Route("/repeat", context -> {
            calls.incrementAndGet();
            return feed();
        }));
        FetchScheduler scheduler = scheduler(runtime, null);
        try {
            RouteConfig config = runtime.routeConfigStore().get("/repeat");
            scheduler.fetchNow(config);
            scheduler.fetchNow(config);

            assertEquals(2, calls.get());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void ticksWithoutPersistence() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AppRuntime runtime = runtime(new Route("/scheduled", context -> {
            started.countDown();
            return feed();
        }));
        FetchScheduler scheduler = scheduler(runtime, null);
        try {
            scheduler.tick();

            assertTrue(started.await(2, TimeUnit.SECONDS), "a missing optional store must not suppress scheduled fetches");
        } finally {
            scheduler.shutdown();
        }
    }

    private static void executeSql(Path database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private AppRuntime runtime(Route... routes) {
        RouteConfigStore configs = new RouteConfigStore(tempDir.resolve("routes.json"), objectMapper);
        for (Route route : routes) {
            configs.save(new RouteConfig(route.path(), route.path(), true, 300, 1800, 1));
        }
        return TestPages.runtime(configs, routes);
    }

    private FetchScheduler scheduler(AppRuntime runtime, FeedStore store) {
        return new FetchScheduler(runtime, store, null,
                new AiConfigStore(tempDir.resolve("ai.json"), objectMapper), null);
    }

    private static Feed feed() {
        return new Feed("Test", "https://example.com", "Test feed", List.of(
                new FeedItem("Article", "https://example.com/article", "Article body", null, null, List.of())));
    }
}
