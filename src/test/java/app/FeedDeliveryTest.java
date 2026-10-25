package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.Feed;
import app.core.FeedItem;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedDeliveryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void formatsAndLimitsShareOneCompleteFeed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AppRuntime runtime = runtime(context -> {
            calls.incrementAndGet();
            return feed();
        });
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), mapper)) {
            RssLiteResource resource = new RssLiteResource(runtime, mapper, store);

            var limited = resource.get("feed", "json", "1");
            assertEquals(200, limited.getStatusCode().value());
            assertEquals(1, mapper.readTree(limited.getBody().toString()).get("items").size());
            assertEquals(3, store.loadItems("/feed").size());

            var rss = resource.get("feed", "rss", "2");
            assertEquals(200, rss.getStatusCode().value());
            assertTrue(rss.getBody().toString().contains("Article B"));
            assertFalse(rss.getBody().toString().contains("Article C"));
            var full = resource.get("feed", "json", null);
            assertEquals(3, mapper.readTree(full.getBody().toString()).get("items").size());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void changingFeedUrlReloadsTheMountedFeed() {
        AppRuntime runtime = runtime(context -> new Feed(context.feedUrl(),
                "https://example.com", "", feed().items()));
        runtime.routeConfigStore().save(config("https://example.com/first.xml"));
        RssLiteResource resource = new RssLiteResource(runtime, mapper);
        assertTrue(resource.get("feed", "json", null).getBody().toString().contains("first.xml"));

        runtime.routeConfigStore().save(config("https://example.com/second.xml"));

        assertTrue(resource.get("feed", "json", null).getBody().toString().contains("second.xml"));
    }

    @Test
    void invalidFormatIsRejectedBeforeFetching() {
        AtomicInteger calls = new AtomicInteger();
        RssLiteResource resource = new RssLiteResource(runtime(context -> {
            calls.incrementAndGet();
            return feed();
        }), mapper);

        assertEquals(400, resource.get("feed", "yaml", null).getStatusCode().value());
        assertEquals(0, calls.get());
    }

    @Test
    void offlineFallbackHonorsLimitAndBlankDefaultFormat() throws Exception {
        AppRuntime runtime = runtime(context -> {
            throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "upstream unavailable");
        });
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), mapper)) {
            store.saveItems("/feed", feed().items());
            RssLiteResource resource = new RssLiteResource(runtime, mapper, store);

            var json = resource.get("feed", "json", "1");
            assertEquals(200, json.getStatusCode().value());
            assertEquals(1, mapper.readTree(json.getBody().toString()).get("items").size());
            var rss = resource.get("feed", " ", "1");
            assertEquals(200, rss.getStatusCode().value());
            assertEquals(1, rss.getBody().toString().split("<item>", -1).length - 1);
        }
    }

    @Test
    void emptyFeedIsLoggedAsFailureAndCanRecoverImmediately() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AppRuntime runtime = runtime(context -> calls.incrementAndGet() == 1
                ? new Feed("Empty", "https://example.com", "", List.of()) : feed());
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), mapper)) {
            RssLiteResource resource = new RssLiteResource(runtime, mapper, store);

            assertEquals(502, resource.get("feed", "json", null).getStatusCode().value());
            assertFalse(store.fetchState().loadRouteHealth().get("/feed").healthy());
            assertEquals(200, resource.get("feed", "json", null).getStatusCode().value());
            assertEquals(2, calls.get());
        }
    }

    private AppRuntime runtime(RouteHandler handler) {
        RouteConfigStore configs = new RouteConfigStore(tempDir.resolve("routes.json"), mapper);
        configs.save(config(null));
        return TestPages.runtime(configs, new Route("/feed", handler));
    }

    private RouteConfig config(String feedUrl) {
        return new RouteConfig("/feed", "/feed", true, 300, 1800, 0, null, feedUrl, null);
    }

    private Feed feed() {
        return new Feed("Example", "https://example.com", "Example feed", List.of(
                new FeedItem("Article A", "https://example.com/a", "A", Instant.EPOCH, null, List.of()),
                new FeedItem("Article B", "https://example.com/b", "B", Instant.EPOCH, null, List.of()),
                new FeedItem("Article C", "https://example.com/c", "C", Instant.EPOCH, null, List.of())));
    }
}
