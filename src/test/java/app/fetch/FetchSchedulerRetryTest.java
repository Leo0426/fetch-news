package app.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.AppRuntime;
import app.TestPages;
import app.ai.AiConfigStore;
import app.core.FetchClient;
import app.core.FetchResponse;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.routes.GenericRssRoute;
import app.store.FeedRefreshState;
import app.store.FeedStore;
import app.store.FetchStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RSS failure backoff in the scheduler: retries wait for their deadline, follow the current feed URL
 * and hand back to the normal schedule.
 */
class FetchSchedulerRetryTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Test
    void skipsRssBackoffAndRetriesWhenDueEvenBeforeTheNormalSchedule() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AppRuntime runtime = runtime(new Route("/rss", new GenericRssRoute(requestedUrl -> {
            calls.incrementAndGet();
            return rss();
        })));
        String url = "https://example.com/feed";
        runtime.routeConfigStore().save(new RouteConfig("/news", "/rss", true,
                300, 1800, 1440, null, url, null));
        runtime.routeConfigStore().save(new RouteConfig("/rss", false, 300, 1800));
        try (FeedStore store = new FeedStore(tempDir.resolve("retry.db"), objectMapper)) {
            Instant now = Instant.now();
            store.fetchState().saveFeedRefreshState(new FeedRefreshState("/news", url, now,
                    null, null, now.plusSeconds(3600), 1, 503, 0, 10, "unavailable"));
            FetchScheduler waiting = scheduler(runtime, store);
            waiting.tick();
            waiting.shutdown();
            assertEquals(0, calls.get(), "tick must not dispatch RSS while its persisted retry is in the future");

            store.fetchState().logFetch("/news", now, 10, 0, 0, "unavailable");
            store.fetchState().saveFeedRefreshState(new FeedRefreshState("/news", url, now,
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
        AppRuntime runtime = runtime(new Route("/rss", new GenericRssRoute(requestedUrl -> {
            calls.incrementAndGet();
            return rss();
        })));
        runtime.routeConfigStore().save(new RouteConfig("/news", "/rss", true,
                300, 1800, 1440, null, "https://example.com/new", null));
        runtime.routeConfigStore().save(new RouteConfig("/rss", false, 300, 1800));
        try (FeedStore store = new FeedStore(tempDir.resolve("changed.db"), objectMapper)) {
            Instant now = Instant.now();
            store.fetchState().saveFeedRefreshState(new FeedRefreshState("/news", "https://example.com/old", now,
                    null, null, now.plusSeconds(3600), 1, 503, 0, 10, "unavailable"));
            store.fetchState().logFetch("/news", now.minusSeconds(60), 10, 1, 1, null);
            store.fetchState().logFetch("/news", now, 10, 0, 0, "unavailable");
            FetchScheduler scheduler = scheduler(runtime, store);
            scheduler.tick();
            scheduler.shutdown();
            assertEquals(1, calls.get());
        }
    }

    @Test
    void fixedRssRetriesAfterItsDeadlineThenResumesItsNormalSchedule() throws Exception {
        String path = "/fixed/news";
        String url = "https://example.com/fixed.xml";
        AtomicInteger calls = new AtomicInteger();
        FetchClient client = new FetchClient() {
            @Override public String get(String requestedUrl) {
                throw new AssertionError("RSS requests must use the response-aware client");
            }

            @Override public FetchResponse fetch(String requestedUrl, Map<String, String> headers) {
                assertEquals(url, requestedUrl);
                if (calls.incrementAndGet() == 1)
                    return new FetchResponse(429, Map.of("Retry-After", List.of("3600")), "Rate limited");
                return new FetchResponse(200, Map.of(), rss());
            }
        };
        try (FeedStore store = new FeedStore(tempDir.resolve("fixed-retry.db"), objectMapper)) {
            GenericRssRoute route = new GenericRssRoute(client, store) {
                @Override public String feedUrl(RouteContext context) { return url; }
            };
            AppRuntime runtime = runtime(new Route(path, route));
            runtime.routeConfigStore().save(new RouteConfig(path, path, true, 300, 1800, 1440));

            tickAndWait(runtime, store);
            FeedRefreshState failure = store.fetchState().loadFeedRefreshState(path, url);
            assertEquals(429, failure.lastHttpStatus());
            assertTrue(failure.nextRetryAt().isAfter(Instant.now().plusSeconds(3500)));
            tickAndWait(runtime, store);
            assertEquals(1, calls.get(), "Retry-After must suppress upstream requests while waiting");

            store.fetchState().saveFeedRefreshState(new FeedRefreshState(path, url, failure.lastCheckedAt(),
                    null, null, Instant.now().minusSeconds(1), 1, 429, 0, 1, "Rate limited"));
            tickAndWait(runtime, store);
            assertEquals(2, calls.get(), "an expired retry must not wait for the daily schedule");
            assertEquals(1, store.loadItems(path).size());
            FeedRefreshState success = store.fetchState().loadFeedRefreshState(path, url);
            assertNull(success.nextRetryAt());
            tickAndWait(runtime, store);
            assertEquals(2, calls.get(), "a successful retry must resume the normal schedule");

            Instant previousDay = Instant.now().minusSeconds(172800);
            store.fetchState().saveFeedRefreshState(new FeedRefreshState(path, url, previousDay,
                    previousDay, previousDay, null, 0, 200, 100, 1, null));
            tickAndWait(runtime, store);
            assertEquals(3, calls.get(), "the refresh check time must control the next scheduled fetch");
        }
    }

    @Test
    void failingHandlerRoutesBackOffInsteadOfRetryingEveryTick() throws Exception {
        Map<String, AtomicInteger> calls = Map.of(
                "/recent-failure", new AtomicInteger(), "/repeated-failures", new AtomicInteger(),
                "/backoff-elapsed", new AtomicInteger());
        AppRuntime runtime = runtime(calls.keySet().stream().map(path -> new Route(path, context -> {
            calls.get(path).incrementAndGet();
            throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "risk control");
        })).toArray(Route[]::new));
        try (FeedStore store = new FeedStore(tempDir.resolve("flaky.db"), objectMapper)) {
            Instant now = Instant.now();
            FetchStateStore logs = store.fetchState();
            for (String path : calls.keySet()) logs.logFetch(path, now.minus(Duration.ofHours(2)), 1, 1, 1, null);
            logs.logFetch("/recent-failure", now.minusSeconds(30), 1, 0, 0, "risk control");
            for (int minutes : new int[] {4, 3, 2}) {
                logs.logFetch("/repeated-failures", now.minus(Duration.ofMinutes(minutes)), 1, 0, 0, "risk control");
                logs.logFetch("/backoff-elapsed", now.minus(Duration.ofMinutes(minutes + 3)), 1, 0, 0, "risk control");
            }

            tickAndWait(runtime, store);

            assertEquals(0, calls.get("/recent-failure").get(), "one failure waits a minute despite the old success");
            assertEquals(0, calls.get("/repeated-failures").get(), "three failures wait four minutes");
            assertEquals(1, calls.get("/backoff-elapsed").get());
        }
        assertEquals(Duration.ofMinutes(1), FetchScheduler.retryDelay(1));
        assertEquals(Duration.ofMinutes(32), FetchScheduler.retryDelay(6));
        assertEquals(Duration.ofMinutes(60), FetchScheduler.retryDelay(50));
    }

    private void tickAndWait(AppRuntime runtime, FeedStore store) {
        FetchScheduler scheduler = scheduler(runtime, store);
        try {
            scheduler.tick();
        } finally {
            scheduler.shutdown();
        }
    }

    private static String rss() {
        return "<rss version=\"2.0\"><channel><title>News</title><link>https://example.com</link>"
                + "<item><title>Article</title><link>https://example.com/article</link></item></channel></rss>";
    }
}
