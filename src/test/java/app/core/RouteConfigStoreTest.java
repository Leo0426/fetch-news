package app.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for route configuration persistence and listing.
 */
class RouteConfigStoreTest {
    @TempDir
    Path tempDir;

    /**
     * Verifies that missing config files produce defaults for registered routes.
     */
    @Test
    void returnsDefaultsForRegisteredRoutesWhenFileDoesNotExist() {
        RouteConfigStore store = new RouteConfigStore(tempDir.resolve("routes.json"), new ObjectMapper());

        List<RouteConfig> configs = store.list(List.of("/example", "/another"));

        assertEquals(2, configs.size());
        assertFalse(configs.getFirst().enabled());
        assertFalse(configs.getLast().enabled());
        assertEquals(300, configs.getFirst().routeCacheTtlSeconds());
        assertEquals(1800, configs.getFirst().detailCacheTtlSeconds());
    }

    /**
     * Verifies that saved route settings survive a store reload.
     */
    @Test
    void savesAndReloadsRouteConfig() throws Exception {
        Path configFile = tempDir.resolve("routes.json");
        RouteConfigStore store = new RouteConfigStore(configFile, new ObjectMapper());

        RouteConfig saved = store.save(new RouteConfig("/example", false, 60, 600));
        RouteConfigStore reloaded = new RouteConfigStore(configFile, new ObjectMapper());

        assertFalse(saved.enabled());
        assertEquals(60, reloaded.get("/example").routeCacheTtlSeconds());
        assertFalse(reloaded.get("/example").enabled());
        assertTrue(Files.exists(configFile));
    }

    /**
     * Verifies that saved routes unknown to the registry are hidden from the list API.
     */
    @Test
    void listIgnoresUnknownRoutesFromConfigFile() throws Exception {
        Path configFile = tempDir.resolve("routes.json");
        Files.writeString(configFile, """
                {
                  "routes": {
                    "/unknown": {
                      "enabled": false,
                      "routeCacheTtlSeconds": 1,
                      "detailCacheTtlSeconds": 1
                    },
                    "/example": {
                      "enabled": false,
                      "routeCacheTtlSeconds": 60,
                      "detailCacheTtlSeconds": 600
                    }
                  }
                }
                """);

        RouteConfigStore store = new RouteConfigStore(configFile, new ObjectMapper());
        List<RouteConfig> configs = store.list(List.of("/example"));

        assertEquals(1, configs.size());
        assertEquals("/example", configs.getFirst().path());
        assertFalse(configs.getFirst().enabled());
    }

    /**
     * Verifies that malformed config files fail with a clear route exception.
     */
    @Test
    void malformedConfigFileThrowsClearRouteException() throws Exception {
        Path configFile = tempDir.resolve("routes.json");
        Files.writeString(configFile, """
                {
                  "routes": []
                }
                """);

        RouteException exception = assertThrows(
                RouteException.class,
                () -> new RouteConfigStore(configFile, new ObjectMapper()));

        assertEquals(RouteError.INVALID_PARAMETER, exception.error());
        assertEquals("route config file must contain a routes object", exception.getMessage());
    }

    /**
     * Verifies that failed writes do not update the in-memory configuration.
     */
    @Test
    void saveFailureDoesNotMutateInMemoryConfig() throws Exception {
        Path configFile = tempDir.resolve("routes.json");
        RouteConfigStore store = new RouteConfigStore(configFile, new ObjectMapper());
        RouteConfig original = store.save(new RouteConfig("/example", true, 120, 900));
        Files.delete(configFile);
        Files.createDirectory(configFile);

        RouteException exception = assertThrows(
                RouteException.class,
                () -> store.save(new RouteConfig("/example", false, 60, 600)));

        assertEquals(RouteError.INVALID_PARAMETER, exception.error());
        assertEquals(original, store.get("/example"));
    }

    /**
     * Verifies that mount aliases are listed immediately after their source route.
     */
    @Test
    void listGroupsSavedMountAliasesWithTheirSourceRoutes() {
        RouteConfigStore store = new RouteConfigStore(tempDir.resolve("routes.json"), new ObjectMapper());

        store.save(new RouteConfig("/another-short", "/another", true, 120, 900));
        store.save(new RouteConfig("/short", "/example", true, 120, 900));
        List<RouteConfig> configs = store.list(List.of("/example", "/another"));

        assertEquals(List.of("/example", "/short", "/another", "/another-short"),
                configs.stream().map(RouteConfig::path).toList());
        assertEquals("/example", configs.get(0).sourcePath());
        assertEquals("/example", configs.get(1).sourcePath());
        assertEquals("/another", configs.get(3).sourcePath());
    }

    /**
     * Verifies that mount alias source paths are persisted and reloaded.
     */
    @Test
    void reloadsMountAliasSourcePath() {
        Path configFile = tempDir.resolve("routes.json");
        RouteConfigStore store = new RouteConfigStore(configFile, new ObjectMapper());

        store.save(new RouteConfig("/short", "/example", true, 120, 900));
        RouteConfigStore reloaded = new RouteConfigStore(configFile, new ObjectMapper());

        assertEquals("/example", reloaded.get("/short").sourcePath());
        assertEquals(120, reloaded.get("/short").routeCacheTtlSeconds());
    }

    @Test
    void keepsCategoryAndAutoSummaryThroughReloadAndToggles() {
        Path file = tempDir.resolve("routes.json");
        RouteConfigStore store = new RouteConfigStore(file, new ObjectMapper());
        store.save(new RouteConfig("/my-feed", "/rss", true, 300, 1800, 0, null,
                "https://example.com/feed.xml", null, true, " 科学 "));

        RouteConfig reloaded = new RouteConfigStore(file, new ObjectMapper()).get("/my-feed");
        RouteConfig toggled = reloaded.withEnabled(false).withScheduleMinutes(30);

        assertEquals("科学", reloaded.category());
        assertTrue(reloaded.autoSummary());
        assertEquals("科学", toggled.category());
        assertTrue(toggled.autoSummary());
        assertFalse(toggled.enabled());
        assertEquals(30, toggled.scheduleMinutes());
        assertEquals(null, new RouteConfig("/x", "/x", true, 300, 1800, 0, null, null, null, false, " ").category());
    }
}
