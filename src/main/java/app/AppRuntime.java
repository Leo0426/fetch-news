package app;

import app.core.CacheService;
import app.core.CredentialConfigStore;
import app.core.DefaultFetchClient;
import app.core.FetchClient;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteMatch;
import app.core.RouteRegistry;
import app.core.Topic;
import app.fetch.FeedFetchCoordinator;
import app.routes.GenericRssRoute;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Owns the application-wide services and explicit route registration.
 */
@Component
public class AppRuntime {

    private final FetchClient defaultFetchClient = new DefaultFetchClient();
    private final CacheService cacheService;
    private final FeedFetchCoordinator fetchCoordinator = new FeedFetchCoordinator();
    private final RouteRegistry routeRegistry;
    private final RouteConfigStore routeConfigStore;
    private final CredentialConfigStore credentialConfigStore;

    /**
     * Builds the default runtime used by Spring injection.
     *
     * @param objectMapper JSON mapper shared with configuration persistence
     */
    @Autowired
    public AppRuntime(ObjectMapper objectMapper, FeedStore feedStore) {
        this.cacheService = new CacheService();
        this.credentialConfigStore = new CredentialConfigStore(
                Path.of(System.getProperty("fetch-news.credential-config", "data/credential-config.json")), objectMapper);
        this.routeRegistry = new RouteRegistry(BuiltInRoutes.create(defaultFetchClient, feedStore, objectMapper,
                cacheService, credentialConfigStore));
        this.routeConfigStore = new RouteConfigStore(configPath(), objectMapper);
    }

    public AppRuntime(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    /**
     * Builds a runtime from supplied collaborators for focused unit tests.
     *
     * @param cacheService     cache service used by resources and routes
     * @param routeRegistry    explicit route registry
     * @param routeConfigStore persisted route configuration store
     */
    AppRuntime(CacheService cacheService, RouteRegistry routeRegistry, RouteConfigStore routeConfigStore) {
        this.cacheService = cacheService;
        this.routeRegistry = routeRegistry;
        this.routeConfigStore = routeConfigStore;
        this.credentialConfigStore = new CredentialConfigStore(
                Path.of("build/test-credential-config.json"),
                new ObjectMapper());
    }

    /**
     * Returns the shared cache service.
     *
     * @return cache service instance
     */
    public CacheService cacheService() {
        return cacheService;
    }

    public FetchClient fetchClient() {
        return defaultFetchClient;
    }

    public FeedFetchCoordinator fetchCoordinator() {
        return fetchCoordinator;
    }

    /**
     * Returns the explicit registry of feed routes.
     *
     * @return route registry instance
     */
    public RouteRegistry routeRegistry() {
        return routeRegistry;
    }

    /**
     * Returns the route configuration store.
     *
     * @return route configuration store instance
     */
    public RouteConfigStore routeConfigStore() {
        return routeConfigStore;
    }

    /**
     * Preserves explicit mounts while resolving concrete parameters from registered patterns.
     */
    public RouteMatch resolveRoute(String path, String sourcePath) {
        if (!path.equals(sourcePath) && sourcePath != null && !sourcePath.contains(":")) {
            return routeRegistry.resolve(sourcePath);
        }
        try {
            return routeRegistry.resolve(path);
        } catch (RouteException e) {
            if (e.error() != RouteError.ROUTE_NOT_FOUND) throw e;
            return routeRegistry.resolve(sourcePath);
        }
    }

    /**
     * Template routes — path patterns and the bare generic RSS route — only act as sources for mounts.
     * They are never enabled, fetched or browsed themselves; their mounts are concrete routes.
     */
    public boolean isTemplate(RouteConfig config) {
        if (config.path().contains(":")) return true;
        if (!config.path().equals(config.sourcePath())) return false;
        try {
            return routeRegistry.resolve(config.path()).handler() instanceof GenericRssRoute;
        } catch (RouteException e) {
            return false;
        }
    }

    /** The single source of a route's topic: its own setting, else the category of the route serving it. */
    public String categoryOf(RouteConfig config) {
        return config.category() != null ? config.category() : routeRegistry.categoryOf(config.sourcePath());
    }

    public String categoryOf(String path) {
        return categoryOf(routeConfigStore.get(path));
    }

    /** Topic labels a route may be assigned to, in display order. */
    public List<String> topics() {
        return Topic.labels();
    }

    public String rssFeedUrl(RouteConfig config) {
        RouteMatch match = resolveRoute(config.path(), config.sourcePath());
        if (!(match.handler() instanceof GenericRssRoute rss)) return null;
        return rss.feedUrl(new RouteContext(config.path(), match.context().pathParams(), Map.of(),
                Duration.ofSeconds(config.detailCacheTtlSeconds()), config.feedUrl()));
    }

    public CredentialConfigStore credentialConfigStore() {
        return credentialConfigStore;
    }

    /**
     * Resolves the route configuration file path from a system property.
     *
     * @return configured route config path or the default local file
     */
    private Path configPath() {
        return Path.of(System.getProperty("fetch-news.route-config", "route-config.json"));
    }
}
