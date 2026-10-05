package app.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DefaultFetchClientTest {
    @Test
    void returnsNotModifiedAndResponseHeaders() throws Exception {
        AtomicReference<String> ifNoneMatch = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", exchange -> {
            ifNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().set("ETag", "\"v1\"");
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
        });
        server.start();
        try {
            FetchClient client = new DefaultFetchClient(Duration.ofSeconds(2), "fetch-news-test");

            FetchResponse response = client.fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/feed",
                    Map.of("If-None-Match", "\"v1\""));

            assertEquals(304, response.statusCode());
            assertEquals("", response.body());
            assertEquals("\"v1\"", response.header("eTaG"));
            assertEquals("\"v1\"", ifNoneMatch.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsLambdaClientsCompatibleAndRetainsHeaderAwareOverrides() throws Exception {
        FetchClient lambda = url -> "legacy body";
        FetchClient headerAware = new FetchClient() {
            @Override
            public String get(String url) {
                return "plain body";
            }

            @Override
            public String get(String url, Map<String, String> extraHeaders) {
                return extraHeaders.get("Authorization");
            }
        };

        assertEquals(new FetchResponse(200, Map.of(), "legacy body"), lambda.fetch("unused", Map.of()));
        assertEquals("Bearer token", headerAware.fetch("unused", Map.of("Authorization", "Bearer token")).body());
    }

    @Test
    void responseHeadersAreDefensivelyCopiedAndCaseInsensitive() {
        List<String> values = new ArrayList<>(List.of("first", "second"));
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("ETag", values);
        FetchResponse response = new FetchResponse(200, headers, "body");
        values.clear();
        headers.clear();

        assertEquals("first", response.header("etag"));
        assertNull(response.header("missing"));
        assertThrows(UnsupportedOperationException.class, () -> response.headers().clear());
        assertThrows(UnsupportedOperationException.class, () -> response.headers().get("ETag").clear());
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 404, 429, 500, 503})
    void fetchReturnsEveryStatusWithoutRetrying(int status) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Retry-After", "120");
            respond(exchange, status, "upstream body");
        })) {
            FetchResponse response = client().fetch(server.url(), Map.of());

            assertEquals(status, response.statusCode());
            assertEquals("upstream body", response.body());
            assertEquals("120", response.header("retry-after"));
            assertEquals(1, requests.get());
        }
    }

    @Test
    void getRetainsAuthenticationHeadersAndReturnsSuccessfulBody() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        try (TestServer server = new TestServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            respond(exchange, 200, "feed body");
        })) {
            assertEquals("feed body", client().get(server.url(), Map.of("Authorization", "Bearer private")));
            assertEquals("Bearer private", authorization.get());
            assertEquals("fetch-news-test", userAgent.get());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {304, 404, 429, 503})
    void getRejectsNonSuccessAndDoesNotRetryPolicyStatuses(int status) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, status, "unavailable");
        })) {
            RouteException failure = assertThrows(RouteException.class, () -> client().get(server.url()));

            assertEquals(RouteError.UPSTREAM_REQUEST_FAILED, failure.error());
            assertTrue(failure.getMessage().contains("HTTP " + status));
            assertEquals(1, requests.get());
        }
    }

    @Test
    void getRetriesTransientServerErrorsWithShortBackoff() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            int request = requests.incrementAndGet();
            respond(exchange, request < 3 ? 502 : 200, "feed body");
        })) {
            long started = System.nanoTime();

            assertEquals("feed body", client().get(server.url()));

            assertEquals(3, requests.get());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 650,
                    "retries must leave the upstream time to recover");
        }
    }

    @Test
    void getStopsAfterThreeTransientFailures() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 500, "unavailable");
        })) {
            assertThrows(RouteException.class, () -> client().get(server.url()));

            assertEquals(3, requests.get());
        }
    }

    @Test
    void getRetriesConnectionFailuresAndPreservesTheCause() throws Exception {
        String unavailableUrl;
        try (TestServer server = new TestServer(exchange -> respond(exchange, 200, "unused"))) {
            unavailableUrl = server.url();
        }
        long started = System.nanoTime();

        RouteException failure = assertThrows(RouteException.class, () -> client().get(unavailableUrl));

        assertEquals(RouteError.UPSTREAM_REQUEST_FAILED, failure.error());
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 650,
                "connection failures must also back off between attempts");
    }

    @Test
    void limitsSameHostAcrossPortsAndAllowsOtherHosts() throws Exception {
        CountDownLatch twoActive = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch thirdArrived = new CountDownLatch(1);
        try (TestServer blockedServer = new TestServer(exchange -> {
            twoActive.countDown();
            awaitRelease(release);
            respond(exchange, 200, "released");
        }); TestServer otherServer = new TestServer(exchange -> {
            if (exchange.getRequestHeaders().getFirst("Host").startsWith("127.0.0.1:")) {
                thirdArrived.countDown();
            }
            respond(exchange, 200, "other");
        }); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            FetchClient client = client();
            var first = executor.submit(() -> client.fetch(blockedServer.url(), Map.of()));
            var second = executor.submit(() -> client.fetch(blockedServer.url(), Map.of()));
            try {
                assertTrue(twoActive.await(2, TimeUnit.SECONDS));
                var third = executor.submit(() -> client.fetch(otherServer.url(), Map.of()));

                assertFalse(thirdArrived.await(200, TimeUnit.MILLISECONDS), "a different port shares the host limit");
                assertEquals("other", client.fetch(otherServer.url().replace("127.0.0.1", "localhost"), Map.of()).body());
                release.countDown();

                assertEquals("released", first.get(3, TimeUnit.SECONDS).body());
                assertEquals("released", second.get(3, TimeUnit.SECONDS).body());
                assertEquals("other", third.get(3, TimeUnit.SECONDS).body());
                assertTrue(thirdArrived.await(1, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void dropsExtraHeadersWhenRedirectChangesOrigin(boolean sameHost) throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> etag = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        try (TestServer destination = new TestServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            etag.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            respond(exchange, 200, "redirected body");
        }); TestServer source = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("Location", destination.url());
            respond(exchange, 302, "redirect");
        })) {
            String url = sameHost ? source.url() : source.url().replace("127.0.0.1", "localhost");

            FetchResponse response = client().fetch(url, Map.of("Authorization", "Bearer secret",
                    "If-None-Match", "\"private-etag\"", "X-Api-Key", "secret-key"));

            assertEquals("redirected body", response.body());
            assertNull(authorization.get());
            assertNull(etag.get());
            assertNull(apiKey.get());
        }
    }

    @Test
    void preservesExtraHeadersWhenRedirectStaysOnSameOrigin() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> etag = new AtomicReference<>();
        try (TestServer server = new TestServer(exchange -> {
            if (exchange.getRequestURI().getRawQuery() == null) {
                exchange.getResponseHeaders().set("Location", "/feed?redirected=true");
                respond(exchange, 302, "redirect");
            } else {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                etag.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
                respond(exchange, 200, "redirected body");
            }
        })) {
            FetchResponse response = client().fetch(server.url(), Map.of("Authorization", "Bearer secret",
                    "If-None-Match", "\"private-etag\""));

            assertEquals("redirected body", response.body());
            assertEquals("Bearer secret", authorization.get());
            assertEquals("\"private-etag\"", etag.get());
        }
    }

    @Test
    void stopsAfterFiveRedirectHops() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/feed");
            respond(exchange, 302, "redirect");
        })) {
            assertEquals(302, client().fetch(server.url(), Map.of()).statusCode());
            assertEquals(6, requests.get());
        }
    }

    @Test
    void limitsRedirectsFromDifferentHostsToTheSameDestinationHost() throws Exception {
        CountDownLatch twoActive = new CountDownLatch(2);
        CountDownLatch thirdArrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        try (TestServer destination = new TestServer(exchange -> {
            if (requests.incrementAndGet() > 2) {
                thirdArrived.countDown();
            }
            twoActive.countDown();
            awaitRelease(release);
            respond(exchange, 200, "destination body");
        }); TestServer firstSource = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("Location", destination.url());
            respond(exchange, 302, "redirect");
        }); TestServer secondSource = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("Location", destination.url());
            respond(exchange, 302, "redirect");
        }); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            FetchClient client = client();
            String firstHostUrl = firstSource.url().replace("127.0.0.1", "localhost");
            var first = executor.submit(() -> client.fetch(firstHostUrl, Map.of()));
            var second = executor.submit(() -> client.fetch(firstHostUrl, Map.of()));
            try {
                assertTrue(twoActive.await(2, TimeUnit.SECONDS));
                var third = executor.submit(() -> client.fetch(secondSource.url(), Map.of()));
                var fourth = executor.submit(() -> client.fetch(secondSource.url(), Map.of()));

                assertFalse(thirdArrived.await(200, TimeUnit.MILLISECONDS),
                        "redirects must share the destination host concurrency limit");
                release.countDown();

                for (var future : List.of(first, second, third, fourth)) {
                    assertEquals("destination body", future.get(3, TimeUnit.SECONDS).body());
                }
                assertEquals(4, requests.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void propagatesInterruptionWhileSendingAndReleasesHostPermit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            if (requests.incrementAndGet() == 1) {
                entered.countDown();
                awaitRelease(release);
            }
            respond(exchange, 200, "body");
        })) {
            FetchClient client = client();
            CompletableFuture<Throwable> failure = new CompletableFuture<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread caller = Thread.startVirtualThread(() -> {
                try {
                    client.get(server.url());
                    failure.complete(null);
                } catch (Throwable e) {
                    interrupted.set(Thread.currentThread().isInterrupted());
                    failure.complete(e);
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                caller.interrupt();

                assertInstanceOf(InterruptedException.class, failure.get(2, TimeUnit.SECONDS));
                assertTrue(interrupted.get());
                assertEquals(1, requests.get());
                assertEquals("body", client.fetch(server.url(), Map.of()).body());
            } finally {
                release.countDown();
                caller.interrupt();
                caller.join(2000);
            }
        }
    }

    @Test
    void propagatesInterruptionWhileWaitingForHostPermit() throws Exception {
        CountDownLatch twoActive = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            twoActive.countDown();
            awaitRelease(release);
            respond(exchange, 200, "body");
        }); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            FetchClient client = client();
            var first = executor.submit(() -> client.fetch(server.url(), Map.of()));
            var second = executor.submit(() -> client.fetch(server.url(), Map.of()));
            try {
                assertTrue(twoActive.await(2, TimeUnit.SECONDS));
                CompletableFuture<Throwable> failure = new CompletableFuture<>();
                AtomicBoolean interrupted = new AtomicBoolean();
                CountDownLatch calling = new CountDownLatch(1);
                Thread caller = Thread.startVirtualThread(() -> {
                    calling.countDown();
                    try {
                        client.fetch(server.url(), Map.of());
                        failure.complete(null);
                    } catch (Throwable e) {
                        interrupted.set(Thread.currentThread().isInterrupted());
                        failure.complete(e);
                    }
                });

                assertTrue(calling.await(2, TimeUnit.SECONDS));
                assertFalse(failure.isDone());
                caller.interrupt();
                assertInstanceOf(InterruptedException.class, failure.get(2, TimeUnit.SECONDS));
                assertTrue(interrupted.get());
                assertEquals(2, requests.get());
                caller.join(2000);
            } finally {
                release.countDown();
            }
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
        }
    }

    private static DefaultFetchClient client() {
        return new DefaultFetchClient(Duration.ofSeconds(5), "fetch-news-test");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 304 ? -1 : bytes.length);
            if (status != 304) {
                exchange.getResponseBody().write(bytes);
            }
        }
    }

    private static void awaitRelease(CountDownLatch release) throws IOException {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IOException("server handler was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private TestServer(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/feed", handler);
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/feed";
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
