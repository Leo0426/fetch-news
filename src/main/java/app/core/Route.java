package app.core;

/**
 * Explicit route registration entry.
 *
 * @param path          stable public path pattern for the route
 * @param handler       handler that produces the normalized feed
 * @param description   short human-readable description shown in the route config UI
 * @param category      display category used to group routes in the sidebar
 * @param fetchStrategy data-acquisition type for badge colouring: "RSS 代理", "JSON API",
 *                      "HTML 解析", or "通用 RSS"
 * @param fetchDetail   one-line explanation of what specifically the handler fetches and parses
 */
public record Route(String path, RouteHandler handler, String description,
                    String category, String fetchStrategy, String fetchDetail) {
    public Route {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("route path must start with /");
        }
    }

    public Route(String path, RouteHandler handler) {
        this(path, handler, null, null, null, null);
    }

    public Route(String path, RouteHandler handler, String description) {
        this(path, handler, description, null, null, null);
    }

    public Route(String path, RouteHandler handler, String description, String category) {
        this(path, handler, description, category, null, null);
    }

    public Route(String path, RouteHandler handler, String description,
                 String category, String fetchStrategy) {
        this(path, handler, description, category, fetchStrategy, null);
    }
}
