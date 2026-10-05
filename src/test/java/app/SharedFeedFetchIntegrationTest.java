package app;

import app.ai.AiConfigStore;
import app.core.CacheService;
import app.core.DefaultFetchClient;
import app.core.FetchDeferredException;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.RouteContext;
import app.core.RouteMatch;
import app.core.RouteRegistry;
import app.routes.GenericRssRoute;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SharedFeedFetchIntegrationTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deferredRefreshDoesNotAddFetchLog() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("deferred.db"), mapper)) {
            FetchDeferredException failure = new FetchDeferredException(Instant.parse("2026-10-04T08:00:00Z"));
            RouteContext context = new RouteContext("/news", Map.of(), Map.of());
            RouteMatch match = new RouteMatch(ignored -> { throw failure; }, context);
            FeedFetcher fetcher = new FeedFetcher(store);

            assertSame(failure, assertThrows(FetchDeferredException.class,
                    () -> fetcher.fetch(match, context, "/news")));
            assertEquals(0, store.getRecentLogs("/news", 10).size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void httpAndSchedulerShareOneUpstreamRequestAndOneFetchLog(boolean httpFirst) throws Exception {
        try (Upstream upstream = new Upstream();
             FeedStore store = new FeedStore(tempDir.resolve("feed.db"), mapper)) {
            RouteConfig config = config(upstream.url("/slow"));
            AppRuntime runtime = runtime(store, config);
            RssLiteResource resource = new RssLiteResource(runtime, mapper, store);
            FetchScheduler scheduler = scheduler(runtime, store);
            Task<ResponseEntity<?>> http = null;
            Task<Void> scheduled = null;
            try {
                if (httpFirst) {
                    http = start(() -> resource.get("news", "json", null));
                    assertTrue(upstream.started.await(5, TimeUnit.SECONDS));
                    scheduled = start(() -> { scheduler.fetchNow(config); return null; });
                    awaitBlocked(scheduled.thread());
                } else {
                    scheduled = start(() -> { scheduler.fetchNow(config); return null; });
                    assertTrue(upstream.started.await(5, TimeUnit.SECONDS));
                    http = start(() -> resource.get("news", "json", null));
                    awaitBlocked(http.thread());
                }
                upstream.release.countDown();

                ResponseEntity<?> response = http.result().get(5, TimeUnit.SECONDS);
                scheduled.result().get(5, TimeUnit.SECONDS);

                assertEquals(200, response.getStatusCode().value());
                assertTrue(response.getBody().toString().contains("Slow article"));
                assertEquals(1, upstream.slowCalls.get());
                assertEquals(1, store.loadItems("/news").size());
                assertEquals(1, store.getRecentLogs("/news", 10).size());
                assertEquals("ok", store.getRecentLogs("/news", 10).getFirst().status());
            } finally {
                upstream.release.countDown();
                if (http != null) http.thread().join(5_000);
                if (scheduled != null) scheduled.thread().join(5_000);
                scheduler.shutdown();
            }
        }
    }

    @Test
    void changedFeedUrlLoadsIndependentlyWhileOldHttpRequestIsRunning() throws Exception {
        try (Upstream upstream = new Upstream();
             FeedStore store = new FeedStore(tempDir.resolve("feed.db"), mapper)) {
            RouteConfig oldConfig = config(upstream.url("/slow"));
            AppRuntime runtime = runtime(store, oldConfig);
            RssLiteResource resource = new RssLiteResource(runtime, mapper, store);
            FetchScheduler scheduler = scheduler(runtime, store);
            Task<ResponseEntity<?>> oldRequest = start(() -> resource.get("news", "json", null));
            Task<Void> scheduled = null;
            try {
                assertTrue(upstream.started.await(5, TimeUnit.SECONDS));
                RouteConfig newConfig = config(upstream.url("/fast"));
                runtime.routeConfigStore().save(newConfig);
                scheduled = start(() -> { scheduler.fetchNow(newConfig); return null; });
                scheduled.result().get(5, TimeUnit.SECONDS);

                assertEquals(1, upstream.fastCalls.get());
                assertFalse(oldRequest.result().isDone());
                upstream.release.countDown();
                assertEquals(200, oldRequest.result().get(5, TimeUnit.SECONDS).getStatusCode().value());
                assertEquals(1, upstream.slowCalls.get());
                assertEquals(2, store.loadItems("/news").size());
                assertEquals(2, store.getRecentLogs("/news", 10).size());
            } finally {
                upstream.release.countDown();
                oldRequest.thread().join(5_000);
                if (scheduled != null) scheduled.thread().join(5_000);
                scheduler.shutdown();
            }
        }
    }

    private AppRuntime runtime(FeedStore store, RouteConfig config) {
        RouteConfigStore configs = new RouteConfigStore(tempDir.resolve("routes.json"), mapper);
        configs.save(config);
        RouteRegistry registry = new RouteRegistry(List.of(
                new Route("/rss", new GenericRssRoute(new DefaultFetchClient(), store))));
        return new AppRuntime(new CacheService(), registry, configs);
    }

    private FetchScheduler scheduler(AppRuntime runtime, FeedStore store) {
        return new FetchScheduler(runtime, store, null,
                new AiConfigStore(tempDir.resolve("ai.json"), mapper), null);
    }

    private static RouteConfig config(String feedUrl) {
        return new RouteConfig("/news", "/rss", true, 300, 1800, 1, null, feedUrl, null);
    }

    private static <T> Task<T> start(Callable<T> task) {
        FutureTask<T> result = new FutureTask<>(task);
        Thread thread = Thread.ofVirtual().start(result);
        return new Task<>(thread, result);
    }

    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("caller did not wait for the blocked upstream request");
    }

    private record Task<T>(Thread thread, FutureTask<T> result) {}

    private static class Upstream implements AutoCloseable {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger slowCalls = new AtomicInteger();
        private final AtomicInteger fastCalls = new AtomicInteger();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpServer server;

        private Upstream() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        private String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                boolean slow = exchange.getRequestURI().getPath().equals("/slow");
                if (slow) {
                    slowCalls.incrementAndGet();
                    started.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            exchange.sendResponseHeaders(504, -1);
                            return;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    fastCalls.incrementAndGet();
                }
                String title = slow ? "Slow" : "Fast";
                byte[] body = ("""
                        <rss version="2.0"><channel><title>News</title>
                        <link>https://example.com</link><description>News</description>
                        <item><title>%s article</title><link>https://example.com/%s</link>
                        <description>Article body</description></item></channel></rss>
                        """.formatted(title, title.toLowerCase())).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        @Override
        public void close() {
            release.countDown();
            server.stop(0);
            executor.close();
        }
    }
}
