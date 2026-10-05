package app.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import app.core.RouteConfig;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeishuPusherTest {
    private static final String ROUTE = "/articles";
    private static final String CATEGORY = "技术博客";
    private static final String SUCCESS = "{\"code\":0,\"msg\":\"success\",\"data\":{}}";

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Exception> serverFailure = new AtomicReference<>();
    private final AtomicReference<BeforeReply> beforeReply = new AtomicReference<>();
    private FeedStore feedStore;
    private HttpServer server;
    private ExecutorService serverExecutor;
    private FeishuConfigStore configStore;
    private FeishuPusher pusher;
    private String webhook;

    @BeforeEach
    void setUp() throws Exception {
        feedStore = new FeedStore(tempDir.resolve("feed.db"), mapper);
        configStore = new FeishuConfigStore(tempDir.resolve("feishu.json"), mapper);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.createContext("/hook", exchange -> {
            try (exchange) {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                BeforeReply action = beforeReply.getAndSet(null);
                if (action != null) action.run();
                Reply reply = replies.poll();
                if (reply == null) reply = new Reply(200, SUCCESS);
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception e) {
                serverFailure.compareAndSet(null, e);
            }
        });
        server.start();
        webhook = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        pusher = new FeishuPusher(configStore, mapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
        if (feedStore != null) feedStore.close();
        assertNull(serverFailure.get(), "local webhook handler failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"immediate", "digest"})
    void retriesHttpFailureWithoutSkippingItems(String mode) throws Exception {
        configure(mode, 10);
        saveItem("first");
        Instant now = Instant.now();
        replies.add(new Reply(503, "unavailable"));

        push(mode, now);
        push(mode, now);

        assertEquals(2, requests.size());
        assertTrue(requests.get(1).contains("https://example.com/first"));
        push(mode, now);
        assertEquals(2, requests.size(), "a confirmed batch must not be sent again");
    }

    @ParameterizedTest
    @ValueSource(strings = {"immediate", "digest"})
    void retriesBusinessFailureDespiteHttpSuccess(String mode) throws Exception {
        configure(mode, 10);
        saveItem("first");
        Instant now = Instant.now();
        replies.add(new Reply(200, "{\"code\":9499,\"msg\":\"Bad Request\",\"data\":{}}"));

        push(mode, now);
        push(mode, now);

        assertEquals(2, requests.size());
        assertTrue(requests.get(1).contains("https://example.com/first"));
        push(mode, now);
        assertEquals(2, requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"immediate", "digest"})
    void retriesEntireBatchAfterOnePageFails(String mode) throws Exception {
        configure(mode, 1);
        saveItem("first");
        saveItem("second");
        Instant now = Instant.now();
        replies.add(new Reply(200, SUCCESS));
        replies.add(new Reply(503, "unavailable"));

        push(mode, now);
        assertEquals(2, requests.size());
        push(mode, now);

        assertEquals(4, requests.size());
        String retriedItems = requests.get(2) + requests.get(3);
        assertTrue(retriedItems.contains("https://example.com/first"));
        assertTrue(retriedItems.contains("https://example.com/second"));
        push(mode, now);
        assertEquals(4, requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"immediate", "digest"})
    void keepsItemsDiscoveredWhileSendingForNextPush(String mode) throws Exception {
        configure(mode, 10);
        saveItem("first");
        Instant now = Instant.now();
        beforeReply.set(() -> saveItem("during-send"));

        push(mode, now);
        assertEquals(1, requests.size());
        assertFalse(requests.getFirst().contains("https://example.com/during-send"));
        push(mode, now.plusSeconds(60));

        assertEquals(2, requests.size());
        assertTrue(requests.get(1).contains("https://example.com/during-send"));
        assertFalse(requests.get(1).contains("https://example.com/first"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"immediate", "digest"})
    void skipsOverlappingPushForSameKeyWhileOtherKeysCanProceed(String mode) throws Exception {
        FeishuCategoryConfig categoryConfig =
                new FeishuCategoryConfig(true, "", mode, "0 * * * * *", 0);
        configStore.save(new FeishuConfig(webhook, true, 10,
                Map.of(CATEGORY, categoryConfig, "编程", categoryConfig)));
        saveItem("first");
        feedStore.saveItems("/other", List.of(new FeedItem(
                "Other article", "https://example.com/other", "Body", null, null, List.of())));
        Instant now = Instant.now();
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        beforeReply.set(() -> {
            firstRequestStarted.countDown();
            if (!releaseFirstRequest.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("first request was not released");
            }
        });

        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> firstPush = workers.submit(() -> push(mode, now));
            try {
                assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS));
                workers.submit(() -> push(mode, now)).get(3, TimeUnit.SECONDS);
                assertEquals(1, requests.size(), "the active delivery must not be sent again");

                workers.submit(() -> push(mode, now, "/other", "编程")).get(3, TimeUnit.SECONDS);
                assertEquals(2, requests.size(), "a different delivery key must remain independent");
                assertTrue(requests.get(1).contains("https://example.com/other"));
            } finally {
                releaseFirstRequest.countDown();
            }
            firstPush.get(5, TimeUnit.SECONDS);
        }

        push(mode, now);
        assertEquals(2, requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"code\":9499,\"msg\":\"Bad Request\",\"data\":{}}",
            "{\"code\":9499,\"StatusCode\":0}",
            "{\"StatusCode\":19001,\"StatusMessage\":\"invalid token\"}",
            "{}",
            "not-json"
    })
    void connectionTestRequiresAnExplicitSuccessfulBusinessResponse(String body) {
        replies.add(new Reply(200, body));

        assertFalse(pusher.test(webhook));
    }

    @Test
    void connectionTestAcceptsLegacySuccessfulResponse() {
        replies.add(new Reply(200, "{\"StatusCode\":0,\"StatusMessage\":\"success\"}"));

        assertTrue(pusher.test(webhook));
    }

    private void configure(String mode, int maxItems) {
        configStore.save(new FeishuConfig(webhook, true, maxItems, Map.of(
                CATEGORY, new FeishuCategoryConfig(true, "", mode, "0 * * * * *", 0))));
    }

    private void saveItem(String slug) throws Exception {
        feedStore.saveItems(ROUTE, List.of(new FeedItem(
                slug, "https://example.com/" + slug, "Article body", null, null, List.of())));
    }

    private void push(String mode, Instant now) {
        push(mode, now, ROUTE, CATEGORY);
    }

    private void push(String mode, Instant now, String route, String category) {
        if (FeishuCategoryConfig.MODE_IMMEDIATE.equals(mode)) {
            pusher.pushRouteItems(route, category, "Articles", feedStore);
        } else {
            pusher.checkDigests(now,
                    List.of(new RouteConfig(route, true, 300, 1800)),
                    Map.of(route, category), feedStore);
        }
    }

    private record Reply(int status, String body) {}

    @FunctionalInterface
    private interface BeforeReply {
        void run() throws Exception;
    }
}
