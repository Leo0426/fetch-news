package app.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves incoming paths against explicitly registered route patterns.
 */
public class RouteRegistry {
    private final List<Route> routes;

    /**
     * Creates a registry from explicit route registrations.
     *
     * @param routes registered routes
     */
    public RouteRegistry(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }

    /**
     * Returns all registered routes.
     *
     * @return immutable list of route registrations
     */
    public List<Route> routes() {
        return routes;
    }

    /**
     * Returns all registered route paths.
     *
     * @return registered path patterns
     */
    public List<String> paths() {
        return routes.stream().map(Route::path).toList();
    }

    /**
     * Returns a map of route path pattern to description for all routes that have one.
     *
     * @return path → description, insertion-ordered
     */
    public Map<String, String> descriptions() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Route route : routes) {
            if (route.description() != null && !route.description().isBlank()) {
                map.put(route.path(), route.description());
            }
        }
        return Map.copyOf(map);
    }

    /** Returns a map of route path pattern to fetch detail text for all routes that have one. */
    public Map<String, String> fetchDetails() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Route route : routes) {
            if (route.fetchDetail() != null && !route.fetchDetail().isBlank()) {
                map.put(route.path(), route.fetchDetail());
            }
        }
        return Map.copyOf(map);
    }

    /** Returns a map of route path pattern to fetch strategy for all routes that have one. */
    public Map<String, String> strategies() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Route route : routes) {
            if (route.fetchStrategy() != null && !route.fetchStrategy().isBlank()) {
                map.put(route.path(), route.fetchStrategy());
            }
        }
        return Map.copyOf(map);
    }

    /**
     * Returns a map of route path pattern to category for all routes that have one.
     *
     * @return path → category, insertion-ordered
     */
    public Map<String, String> categories() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Route route : routes) {
            if (route.category() != null && !route.category().isBlank()) {
                map.put(route.path(), route.category());
            }
        }
        return Map.copyOf(map);
    }

    /**
     * Resolves a request path to a handler and path parameters.
     *
     * @param path request path
     * @return matched route and context
     */
    public RouteMatch resolve(String path) {
        String normalized = normalize(path);
        for (Route route : routes) {
            Map<String, String> params = match(route.path(), normalized);
            if (params != null) {
                return new RouteMatch(route.handler(), new RouteContext(normalized, params, Map.of()));
            }
        }
        throw new RouteException(RouteError.ROUTE_NOT_FOUND);
    }

    /**
     * Matches a normalized path against a route pattern.
     *
     * @param pattern route pattern, supporting {@code :name} parameters
     * @param path normalized request path
     * @return captured parameters or {@code null} when not matched
     */
    private Map<String, String> match(String pattern, String path) {
        String[] patternParts = parts(pattern);
        String[] pathParts = parts(path);
        if (patternParts.length != pathParts.length) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < patternParts.length; i++) {
            String patternPart = patternParts[i];
            String pathPart = pathParts[i];
            if (patternPart.startsWith(":")) {
                params.put(patternPart.substring(1), pathPart);
            } else if (!patternPart.equals(pathPart)) {
                return null;
            }
        }
        return params;
    }

    /**
     * Normalizes a path to a leading-slash form without a trailing slash.
     *
     * @param path path to normalize
     * @return normalized path
     */
    private String normalize(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return "/";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    /**
     * Splits a normalized path into route segments.
     *
     * @param path path to split
     * @return route path segments
     */
    private String[] parts(String path) {
        String trimmed = normalize(path).substring(1);
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/");
    }
}
