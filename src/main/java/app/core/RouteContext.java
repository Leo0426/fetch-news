package app.core;

import java.time.Duration;
import java.util.Map;

/**
 * Request context passed to a route handler.
 *
 * @param path         exposed request path
 * @param pathParams   parameters captured from route path patterns
 * @param queryParams  supported query parameters from the request
 * @param detailCacheTtl TTL routes should use for detail-page cache entries
 * @param feedUrl      RSS/Atom URL injected from {@link RouteConfig} for the generic /rss route;
 *                     {@code null} for all other routes
 */
public record RouteContext(
        String path,
        Map<String, String> pathParams,
        Map<String, String> queryParams,
        Duration detailCacheTtl,
        String feedUrl) {

    /** Creates a context with no feedUrl (all standard routes). */
    public RouteContext(String path, Map<String, String> pathParams,
                        Map<String, String> queryParams, Duration detailCacheTtl) {
        this(path, pathParams, queryParams, detailCacheTtl, null);
    }

    /** Creates a context with the default detail cache TTL and no feedUrl. */
    public RouteContext(String path, Map<String, String> pathParams, Map<String, String> queryParams) {
        this(path, pathParams, queryParams,
                Duration.ofSeconds(RouteConfig.DEFAULT_DETAIL_CACHE_TTL_SECONDS), null);
    }

    /** Normalizes null maps and TTL values into immutable defaults. */
    public RouteContext {
        pathParams   = Map.copyOf(pathParams == null ? Map.of() : pathParams);
        queryParams  = Map.copyOf(queryParams == null ? Map.of() : queryParams);
        detailCacheTtl = detailCacheTtl == null
                ? Duration.ofSeconds(RouteConfig.DEFAULT_DETAIL_CACHE_TTL_SECONDS)
                : detailCacheTtl;
    }

    /** Returns a captured path parameter by name. */
    public String pathParam(String name) { return pathParams.get(name); }

    /** Returns a query parameter by name. */
    public String queryParam(String name) { return queryParams.get(name); }
}
