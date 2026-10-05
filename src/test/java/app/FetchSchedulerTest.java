package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.ai.AiConfig;
import app.ai.AiConfigStore;
import app.ai.ArticleExtractor;
import app.ai.ArticleSummarizer;
import app.ai.OllamaClient;
import app.core.CacheService;
import app.core.Feed;
import app.core.FeedItem;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteRegistry;
import app.store.FeedStore;
import app.store.FeedRefreshState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
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
    void successfulFetchPersistsBeforeEnqueuingAndDoesNotWaitForInference() throws Exception {
        AppRuntime runtime = runtime(new Route("/queued", context -> feed()));
        AiConfigStore aiConfig = new AiConfigStore(tempDir.resolve("ai.json"), objectMapper);
        aiConfig.save(new AiConfig("http://127.0.0.1:1", "fixture", true));
        OllamaClient model = new OllamaClient(aiConfig, objectMapper) {
            @Override public Generation generateWithMetadata(String prompt) {
                throw new AssertionError("Fetching must only enqueue summaries");
            }
        };
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), objectMapper);
             ArticleSummarizer summaries = new ArticleSummarizer(model, store,
                     new ArticleExtractor(model, store, url -> { throw new AssertionError("No article fetch expected"); }), aiConfig)) {
            FetchScheduler scheduler = new FetchScheduler(runtime, store, summaries, aiConfig, null);
            try {
                scheduler.fetchNow(runtime.routeConfigStore().get("/queued"));
                assertEquals(1, store.loadItems("/queued").size());
                assertEquals("queued", summaries.progress(feed().items().getFirst().link()).state());
                assertEquals("ok", store.getRecentLogs("/queued", 1).getFirst().status());
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
            scheduler.fetchNow(config);

            assertEquals(2, calls.get());
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

    @Test
    void skipsRssBackoffAndRetriesWhenDueEvenBeforeTheNormalSchedule() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AppRuntime runtime = runtime(new Route("/rss", context -> {
            calls.incrementAndGet();
            return feed();
        }));
        String url = "https://example.com/feed";
        runtime.routeConfigStore().save(new RouteConfig("/news", "/rss", true,
                300, 1800, 1440, null, url, null));
        runtime.routeConfigStore().save(new RouteConfig("/rss", false, 300, 1800));
        try (FeedStore store = new FeedStore(tempDir.resolve("retry.db"), objectMapper)) {
            Instant now = Instant.now();
            store.saveFeedRefreshState(new FeedRefreshState("/news", url, now,
                    null, null, now.plusSeconds(3600), 1, 503, 0, 10, "unavailable"));
            FetchScheduler waiting = scheduler(runtime, store);
            waiting.tick();
            waiting.shutdown();
            assertEquals(0, calls.get(), "tick must not dispatch RSS while its persisted retry is in the future");

            store.logFetch("/news", now, 10, 0, 0, "unavailable");
            store.saveFeedRefreshState(new FeedRefreshState("/news", url, now,
                    null, null, now.minusSeconds(1), 1, 503, 0, 10, "unavailable"));
            FetchScheduler retrying = scheduler(runtime, store);
            retrying.tick();
            retrying.shutdown();
            assertEquals(1, calls.get(), "retry deadline must not wait for the normal daily schedule");
        }
    }

    @Test
    void ignoresBackoffFromAPreviousFeedUrl() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AppRuntime runtime = runtime(new Route("/rss", context -> {
            calls.incrementAndGet();
            return feed();
        }));
        runtime.routeConfigStore().save(new RouteConfig("/news", "/rss", true,
                300, 1800, 1440, null, "https://example.com/new", null));
        runtime.routeConfigStore().save(new RouteConfig("/rss", false, 300, 1800));
        try (FeedStore store = new FeedStore(tempDir.resolve("changed.db"), objectMapper)) {
            Instant now = Instant.now();
            store.saveFeedRefreshState(new FeedRefreshState("/news", "https://example.com/old", now,
                    null, null, now.plusSeconds(3600), 1, 503, 0, 10, "unavailable"));
            store.logFetch("/news", now.minusSeconds(60), 10, 1, 1, null);
            store.logFetch("/news", now, 10, 0, 0, "unavailable");
            FetchScheduler scheduler = scheduler(runtime, store);
            scheduler.tick();
            scheduler.shutdown();
            assertEquals(1, calls.get());
        }
    }

    private AppRuntime runtime(Route... routes) {
        RouteConfigStore configs = new RouteConfigStore(tempDir.resolve("routes.json"), objectMapper);
        for (Route route : routes) {
            configs.save(new RouteConfig(route.path(), route.path(), true, 300, 1800, 1));
        }
        return new AppRuntime(new CacheService(), new RouteRegistry(List.of(routes)), configs);
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
