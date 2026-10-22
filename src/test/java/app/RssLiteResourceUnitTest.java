package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.ai.AiConfigStore;
import app.core.CacheService;
import app.core.Feed;
import app.core.FeedItem;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.RouteRegistry;
import app.routes.GenericRssRoute;
import app.routes.OpenAiNewsRoute;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for resource behavior that is easier to isolate without HTTP.
 */
class RssLiteResourceUnitTest {
    @TempDir
    Path tempDir;

    /**
     * Verifies that disabled routes return before invoking the handler.
     */
    @Test
    void disabledRouteDoesNotCallHandler() {
        AtomicInteger calls = new AtomicInteger();
        AppRuntime runtime = runtimeWithRoute("/count", calls, new RouteConfig("/count", false, 300, 1800));
        RssLiteResource resource = new RssLiteResource(runtime, new ObjectMapper());

        ResponseEntity<?> response = resource.get("count", null, null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals(0, calls.get());
    }

    /**
     * Verifies that route cache TTL settings control final feed cache expiry.
     */
    @Test
    void routeCacheTtlFromConfigControlsFeedCacheExpiry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AppRuntime runtime = runtimeWithRoute("/count", calls, new RouteConfig("/count", true, 1, 1800));
        RssLiteResource resource = new RssLiteResource(runtime, new ObjectMapper());

        resource.get("count", null, null);
        resource.get("count", null, null);
        Thread.sleep(1100);
        resource.get("count", null, null);

        assertEquals(2, calls.get());
    }

    /**
     * Verifies that mount aliases resolve and invoke their configured source route.
     */
    @Test
    void mountAliasResolvesToConfiguredSourceRoute() {
        AtomicInteger calls = new AtomicInteger();
        AppRuntime runtime = runtimeWithRoute("/count", calls, new RouteConfig("/short", "/count", true, 300, 1800));
        RssLiteResource resource = new RssLiteResource(runtime, new ObjectMapper());

        ResponseEntity<?> response = resource.get("short", null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, calls.get());
    }

    @Test
    void builtInSourceDoesNotReplaceAnExistingMountWithTheSamePath() {
        String customUrl = "https://custom.example/feed.xml";
        var config = new RouteConfig("/openai/news", "/rss", true, 300, 1800,
                0, null, customUrl, "My existing feed");
        var configs = new RouteConfigStore(tempDir.resolve("existing-routes.json"), new ObjectMapper());
        configs.save(config);
        var registry = new RouteRegistry(List.of(
                new Route("/openai/news", new OpenAiNewsRoute(url -> {
                    throw new AssertionError("The new built-in must not replace the saved source");
                }, null)),
                new Route("/rss", new GenericRssRoute(url -> {
                    assertEquals(customUrl, url);
                    return "<rss version=\"2.0\"><channel><title>Custom</title><link>https://custom.example/</link>"
                            + "<item><title>Custom article</title><link>https://custom.example/article</link>"
                            + "</item></channel></rss>";
                }))));
        var runtime = new AppRuntime(new CacheService(), registry, configs);
        var response = new RssLiteResource(runtime, new ObjectMapper()).get("openai/news", "json", null);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains("Custom article"));
        assertEquals(customUrl, runtime.rssFeedUrl(config));
        assertEquals(config, configs.get(config.path()));
    }

    @Test
    void concretePathsStillSupplyParametersWhenTheSavedSourceIsAPattern() {
        var registry = new RouteRegistry(List.of(new Route("/github/releases/:owner/:repo", context -> null)));
        var runtime = new AppRuntime(new CacheService(), registry,
                new RouteConfigStore(tempDir.resolve("parameter-routes.json"), new ObjectMapper()));

        var match = runtime.resolveRoute("/github/releases/ollama/ollama", "/github/releases/:owner/:repo");

        assertEquals("ollama", match.context().pathParam("owner"));
        assertEquals("ollama", match.context().pathParam("repo"));
    }

    /**
     * Verifies that scheduled fetches use the same mount-alias resolution as HTTP requests.
     */
    @Test
    void scheduledFetchResolvesMountAliasToConfiguredSourceRoute() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        RouteRegistry registry = new RouteRegistry(List.of(new Route("/source", context -> {
            int call = calls.incrementAndGet();
            return new Feed("Source", "https://example.com/source", "Source feed", List.of(new FeedItem(
                    "Scheduled " + call,
                    "https://example.com/source/" + call,
                    "Scheduled " + call,
                    Instant.parse("2026-05-22T08:00:00Z"),
                    null,
                    List.of())));
        })));
        RouteConfigStore store = new RouteConfigStore(tempDir.resolve("scheduled-routes.json"), objectMapper);
        RouteConfig alias = new RouteConfig("/alias", "/source", true, 300, 1800, 15);
        store.save(alias);
        AppRuntime runtime = new AppRuntime(
                new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30)), registry, store);

        try (FeedStore feedStore = new FeedStore(tempDir.resolve("scheduled-feed.db"), objectMapper)) {
            FetchScheduler scheduler = new FetchScheduler(
                    runtime,
                    feedStore,
                    null,
                    new AiConfigStore(tempDir.resolve("ai-config.json"), objectMapper),
                    null);

            scheduler.fetchNow(alias);

            assertEquals(1, calls.get());
            assertEquals(1, feedStore.loadItems("/alias").size());
            assertEquals(0, feedStore.loadItems("/source").size());
            scheduler.shutdown();
        }
    }

    /**
     * Verifies that the default format is RSS when no format query param is given.
     */
    @Test
    void defaultFormatIsRss() {
        AtomicInteger calls = new AtomicInteger();
        AppRuntime runtime = runtimeWithRoute("/feed", calls, new RouteConfig("/feed", true, 300, 1800));
        RssLiteResource resource = new RssLiteResource(runtime, new ObjectMapper());

        ResponseEntity<?> response = resource.get("feed", null, null);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getContentType().toString().contains("rss+xml"));
        assertTrue(response.getBody().toString().contains("<rss version=\"2.0\">"));
    }

    /**
     * Verifies that JSON format and item limit are applied correctly.
     */
    @Test
    void jsonFormatWithLimitTruncatesFeed() {
        ObjectMapper objectMapper = new ObjectMapper();
        RouteRegistry registry = new RouteRegistry(List.of(new Route("/multi", context ->
                new Feed("Multi", "https://x.example/multi", "d", List.of(
                        new FeedItem("A", "https://x.example/a", "a", Instant.EPOCH, null, List.of()),
                        new FeedItem("B", "https://x.example/b", "b", Instant.EPOCH, null, List.of()),
                        new FeedItem("C", "https://x.example/c", "c", Instant.EPOCH, null, List.of()))))));
        RouteConfigStore store = new RouteConfigStore(tempDir.resolve("multi.json"), objectMapper);
        store.save(new RouteConfig("/multi", true, 300, 1800));
        AppRuntime runtime = new AppRuntime(
                new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30)), registry, store);
        RssLiteResource resource = new RssLiteResource(runtime, objectMapper);

        ResponseEntity<?> response = resource.get("multi", "json", "1");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getContentType().toString().contains("application/json"));
        String json = response.getBody().toString();
        assertTrue(json.contains("\"title\":\"Multi\""));
        assertTrue(json.contains("\"title\":\"A\""));
        assertTrue(!json.contains("\"title\":\"B\"") && !json.contains("\"title\":\"C\""));
    }

    /**
     * Builds a test runtime with a single counting route.
     *
     * @param path registered route path
     * @param calls counter incremented by the route handler
     * @param config route configuration to save before creating the resource
     * @return runtime wired for unit tests
     */
    private AppRuntime runtimeWithRoute(String path, AtomicInteger calls, RouteConfig config) {
        ObjectMapper objectMapper = new ObjectMapper();
        RouteRegistry registry = new RouteRegistry(List.of(new Route(path, context -> {
            int call = calls.incrementAndGet();
            return new Feed("Count", "https://example.com/count", "Counting feed", List.of(new FeedItem(
                    "Call " + call,
                    "https://example.com/count/" + call,
                    "Call " + call,
                    Instant.parse("2026-05-22T08:00:00Z"),
                    null,
                    List.of())));
        })));
        RouteConfigStore store = new RouteConfigStore(tempDir.resolve("routes.json"), objectMapper);
        store.save(config);
        return new AppRuntime(new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30)), registry, store);
    }
}
