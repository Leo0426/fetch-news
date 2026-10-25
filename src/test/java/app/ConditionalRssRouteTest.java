package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.DefaultFetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteMatch;
import app.fetch.FeedFetcher;
import app.routes.GenericRssRoute;
import app.store.FeedStore;
import app.store.HttpFeedSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConditionalRssRouteTest {
    @TempDir Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
    private final MutableClock clock = new MutableClock();
    private HttpServer server;
    private String url;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", exchange -> {
            try (exchange) {
                var headers = new HashMap<String, String>();
                exchange.getRequestHeaders().forEach((key, values) -> headers.put(key.toLowerCase(), values.getFirst()));
                requests.add(headers);
                Reply reply = replies.poll();
                if (reply == null) reply = new Reply(500, "unexpected request", Map.of());
                reply.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), reply.status() == 304 ? -1 : body.length);
                if (reply.status() != 304) exchange.getResponseBody().write(body);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/feed";
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    @Test
    void fixedSourcesReuseConditionalSnapshotsWithoutConfiguredFeedUrls() throws Exception {
        replies.add(new Reply(200, xml("A"), Map.of("ETag", "\"fixed-v1\"")));
        replies.add(new Reply(304, "", Map.of()));
        try (FeedStore store = store()) {
            GenericRssRoute source = new GenericRssRoute(new DefaultFetchClient(), store, clock) {
                public String feedUrl(RouteContext context) { return url; }
            };
            RouteContext context = new RouteContext("/fixed/news", Map.of(), Map.of());
            var first = source.handle(context);
            clock.advance(60);
            assertEquals(first, source.handle(context));
            assertEquals(304, store.fetchState().loadFeedRefreshState("/fixed/news", url).lastHttpStatus());
            assertEquals("\"fixed-v1\"", requests.get(1).get("if-none-match"));
        }
    }

    @Test
    void validatesAfterRestartAndPreservesItemsUntilContentChanges() throws Exception {
        replies.add(new Reply(200, xml("A"), Map.of("ETag", "\"v1\"")));
        replies.add(new Reply(304, "", Map.of()));
        replies.add(new Reply(200, xml("A", "B"), Map.of("ETag", "\"v2\"")));
        Instant firstSeen;
        Instant firstCheck = clock.instant();
        try (FeedStore store = store()) {
            fetch(store);
            firstSeen = store.items().loadNewItemsSince("/news", Instant.EPOCH).getFirst().firstSeenAt();
        }
        clock.advance(60);
        try (FeedStore store = store()) {
            assertEquals(1, fetch(store).items().size());
            assertEquals(firstSeen, store.items().loadNewItemsSince("/news", Instant.EPOCH).getFirst().firstSeenAt());
            assertEquals(304, store.fetchState().loadFeedRefreshState("/news", url).lastHttpStatus());
            assertEquals(firstCheck, store.fetchState().loadFeedRefreshState("/news", url).lastChangedAt());
            assertEquals(0, store.fetchState().getRecentLogs("/news", 1).getFirst().newItemCount());
            clock.advance(60);
            assertEquals(2, fetch(store).items().size());
            assertEquals(clock.instant(), store.fetchState().loadFeedRefreshState("/news", url).lastChangedAt());
            assertEquals(1, store.fetchState().getRecentLogs("/news", 1).getFirst().newItemCount());
        }
        assertEquals(3, requests.size());
        assertNull(requests.getFirst().get("if-none-match"));
        assertEquals("\"v1\"", requests.get(1).get("if-none-match"));
        assertEquals("\"v1\"", requests.get(2).get("if-none-match"));
    }

    @Test
    void persistsRetryAfterAndDoesNotCountDeferredCallsAsNetworkFailures() throws Exception {
        replies.add(new Reply(200, xml("A"), Map.of("ETag", "\"v1\"")));
        replies.add(new Reply(429, "slow down", Map.of("Retry-After", "180")));
        replies.add(new Reply(304, "", Map.of()));
        Instant successfulCheck = clock.instant();
        try (FeedStore store = store()) {
            fetch(store);
            clock.advance(60);
            assertThrows(RouteException.class, () -> fetch(store));
            var state = store.fetchState().loadFeedRefreshState("/news", url);
            assertEquals(clock.instant().plusSeconds(180), state.nextRetryAt());
            assertEquals(successfulCheck, state.lastSuccessAt());
            assertEquals(1, state.consecutiveFailures());
            assertEquals(429, state.lastHttpStatus());
        }
        try (FeedStore store = store()) {
            clock.advance(179);
            assertThrows(RouteException.class, () -> fetch(store));
            assertEquals(2, requests.size());
            assertEquals(2, store.fetchState().getRecentLogs("/news", 10).size());
            assertEquals(1, store.fetchState().loadFeedRefreshState("/news", url).consecutiveFailures());
            clock.advance(1);
            assertEquals(1, fetch(store).items().size());
            assertNull(store.fetchState().loadFeedRefreshState("/news", url).nextRetryAt());
            assertEquals(0, store.fetchState().loadFeedRefreshState("/news", url).consecutiveFailures());
            assertEquals(successfulCheck, store.fetchState().loadFeedRefreshState("/news", url).lastChangedAt());
        }
        assertEquals(3, requests.size());
    }

    @Test
    void honorsHttpDateRetryAfterBeyondTheLocalBackoffCap() throws Exception {
        Instant deadline = clock.instant().plusSeconds(7200);
        replies.add(new Reply(503, "maintenance", Map.of("Retry-After",
                DateTimeFormatter.RFC_1123_DATE_TIME.format(deadline.atZone(ZoneOffset.UTC)))));
        try (FeedStore store = store()) {
            assertThrows(RouteException.class, () -> fetch(store));
            assertEquals(deadline, store.fetchState().loadFeedRefreshState("/news", url).nextRetryAt());
        }
        assertEquals(1, requests.size());
    }

    @Test
    void increasesBackoffToOneHourForRepeatedFailures() throws Exception {
        try (FeedStore store = store()) {
            for (long delay : new long[] {60, 120, 240, 480, 960, 1920, 3600, 3600}) {
                replies.add(new Reply(500, "unavailable", Map.of("Retry-After", "invalid")));
                assertThrows(RouteException.class, () -> fetch(store));
                assertEquals(clock.instant().plusSeconds(delay),
                        store.fetchState().loadFeedRefreshState("/news", url).nextRetryAt());
                clock.advance(delay);
            }
            assertEquals(8, store.fetchState().loadFeedRefreshState("/news", url).consecutiveFailures());
        }
        assertEquals(8, requests.size());
    }

    @Test
    void retriesCold304OnceWithoutConditions() throws Exception {
        replies.add(new Reply(304, "", Map.of()));
        replies.add(new Reply(200, xml("A"), Map.of("ETag", "\"v1\"")));
        try (FeedStore store = store()) {
            assertEquals(1, fetch(store).items().size());
            assertEquals(200, store.fetchState().loadFeedRefreshState("/news", url).lastHttpStatus());
        }
        assertEquals(2, requests.size());
        assertTrue(requests.stream().allMatch(headers -> !headers.containsKey("if-none-match")));
    }

    @Test
    void stopsAfterASecondCold304() throws Exception {
        replies.add(new Reply(304, "", Map.of()));
        replies.add(new Reply(304, "", Map.of()));
        try (FeedStore store = store()) {
            assertThrows(RouteException.class, () -> fetch(store));
            assertEquals(304, store.fetchState().loadFeedRefreshState("/news", url).lastHttpStatus());
            assertNull(store.fetchState().loadHttpFeedSnapshot(url));
        }
        assertEquals(2, requests.size());
    }

    @Test
    void replacesCorruptSnapshotWithAnUnconditionalFetch() throws Exception {
        replies.add(new Reply(200, xml("A"), Map.of("ETag", "\"fresh\"")));
        try (FeedStore store = store()) {
            store.fetchState().saveHttpFeedSnapshot(new HttpFeedSnapshot(url, "not xml", "\"broken\"", null));
            assertEquals(1, fetch(store).items().size());
            assertEquals("\"fresh\"", store.fetchState().loadHttpFeedSnapshot(url).etag());
        }
        assertNull(requests.getFirst().get("if-none-match"));
    }

    @Test
    void keepsValidBodyAndValidatorsWhenAReplacementCannotBeParsed() throws Exception {
        replies.add(new Reply(200, xml("A"), Map.of("ETag", "\"good\"")));
        replies.add(new Reply(200, "<html>upstream challenge</html>", Map.of("ETag", "\"bad\"")));
        replies.add(new Reply(304, "", Map.of()));
        try (FeedStore store = store()) {
            fetch(store);
            clock.advance(60);
            RouteException failure = assertThrows(RouteException.class, () -> fetch(store));
            assertEquals(RouteError.UPSTREAM_INVALID_CONTENT, failure.error());
            assertEquals("\"good\"", store.fetchState().loadHttpFeedSnapshot(url).etag());
            assertEquals(200, store.fetchState().loadFeedRefreshState("/news", url).lastHttpStatus());
            assertNotNull(store.fetchState().loadFeedRefreshState("/news", url).lastError());
            clock.advance(60);
            assertEquals(1, fetch(store).items().size());
        }
        assertEquals("\"good\"", requests.getLast().get("if-none-match"));
    }

    @Test
    void updatesValidatorsOn304AndMeasuresUtf8Bytes() throws Exception {
        String modified = "Sat, 03 Oct 2026 12:00:00 GMT";
        String body = xml("中文");
        replies.add(new Reply(200, body, Map.of("ETag", "\"v1\"", "Last-Modified", modified)));
        replies.add(new Reply(304, "", Map.of("ETag", "\"v2\"")));
        replies.add(new Reply(304, "", Map.of()));
        try (FeedStore store = store()) {
            fetch(store);
            assertEquals(body.getBytes(StandardCharsets.UTF_8).length,
                    store.fetchState().loadFeedRefreshState("/news", url).responseBytes());
            fetch(store);
            assertEquals(0, store.fetchState().loadFeedRefreshState("/news", url).responseBytes());
            fetch(store);
        }
        assertEquals(modified, requests.get(1).get("if-modified-since"));
        assertEquals(modified, requests.get(2).get("if-modified-since"));
        assertEquals("\"v2\"", requests.get(2).get("if-none-match"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Cache-Control:no-store", "Cache-Control:public, private=\"ETag\"",
            "Vary:Accept-Language", "Set-Cookie:session=secret"})
    void discardsSnapshotsForResponsesWithNonPublicCachePolicy(String policy) throws Exception {
        String[] parts = policy.split(":", 2);
        replies.add(new Reply(200, xml("A"), Map.of("ETag", "\"v1\"")));
        replies.add(new Reply(304, "", Map.of(parts[0], parts[1])));
        replies.add(new Reply(200, xml("A"), Map.of(parts[0], parts[1])));
        try (FeedStore store = store()) {
            fetch(store);
            Instant firstChange = store.fetchState().loadFeedRefreshState("/news", url).lastChangedAt();
            clock.advance(60);
            fetch(store);
            assertNull(store.fetchState().loadHttpFeedSnapshot(url));
            clock.advance(60);
            fetch(store);
            assertNull(store.fetchState().loadHttpFeedSnapshot(url));
            assertEquals(firstChange, store.fetchState().loadFeedRefreshState("/news", url).lastChangedAt());
        }
        assertNull(requests.getLast().get("if-none-match"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Cache-Control:no-store", "Cache-Control:private",
            "Vary:Accept-Encoding", "Set-Cookie:session=secret"})
    void discardsDisallowedSnapshotsEvenWhenTheReplacementIsInvalid(String policy) throws Exception {
        String[] parts = policy.split(":", 2);
        replies.add(new Reply(200, xml("A"), Map.of("ETag", "\"v1\"")));
        replies.add(new Reply(200, "<html>invalid feed</html>", Map.of(parts[0], parts[1])));
        try (FeedStore store = store()) {
            fetch(store);
            assertThrows(RouteException.class, () -> fetch(store));
            assertNull(store.fetchState().loadHttpFeedSnapshot(url));
            assertEquals(1, store.loadItems("/news").size());
        }
    }

    @Test
    void rejectsNonHttpUrlsBeforeSendingARequest() throws Exception {
        url = "file:///etc/passwd";
        try (FeedStore store = store()) {
            assertEquals(RouteError.INVALID_PARAMETER,
                    assertThrows(RouteException.class, () -> fetch(store)).error());
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    void tracksChangesPerAliasWhenAnotherAliasUpdatesTheSharedSnapshot() throws Exception {
        replies.add(new Reply(200, xml("A"), Map.of("ETag", "\"v1\"")));
        replies.add(new Reply(200, xml("A", "B"), Map.of("ETag", "\"v2\"")));
        replies.add(new Reply(304, "", Map.of()));
        try (FeedStore store = store()) {
            fetch(store);
            clock.advance(60);
            GenericRssRoute route = new GenericRssRoute(new DefaultFetchClient(), store, clock);
            RouteContext other = new RouteContext("/other", Map.of(), Map.of(), Duration.ofMinutes(30), url);
            route.handle(other);
            clock.advance(60);
            assertEquals(2, fetch(store).items().size());
            assertEquals(clock.instant(), store.fetchState().loadFeedRefreshState("/news", url).lastChangedAt());
        }
    }

    private FeedStore store() throws Exception {
        return new FeedStore(tempDir.resolve("feed.db"), mapper);
    }

    private app.core.Feed fetch(FeedStore store) throws Exception {
        GenericRssRoute route = new GenericRssRoute(new DefaultFetchClient(), store, clock);
        RouteContext context = new RouteContext("/news", Map.of(), Map.of(), Duration.ofMinutes(30), url);
        return new FeedFetcher(store).fetch(new RouteMatch(route, context), context, "/news");
    }

    private static String xml(String... titles) {
        List<String> items = new ArrayList<>();
        for (String title : titles) {
            items.add("<item><title>" + title + "</title><link>https://example.com/" + title + "</link></item>");
        }
        return "<rss version=\"2.0\"><channel><title>News</title><link>https://example.com</link>"
                + String.join("", items) + "</channel></rss>";
    }

    private record Reply(int status, String body, Map<String, String> headers) {}

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-03T12:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
