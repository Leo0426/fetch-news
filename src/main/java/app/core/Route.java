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
 * @param recommendedScheduleMinutes suggested refresh interval when subscribing; 0 means no recommendation
 */
public record Route(String path, RouteHandler handler, String description,
                    Topic topic, String fetchStrategy, String fetchDetail,
                    int recommendedScheduleMinutes) {
    public Route {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("route path must start with /");
        }
        if (recommendedScheduleMinutes < 0) {
            throw new IllegalArgumentException("recommended schedule interval must be non-negative");
        }
    }

    public Route(String path, RouteHandler handler) {
        this(path, handler, null, null, null, null);
    }

    public Route(String path, RouteHandler handler, String description) {
        this(path, handler, description, null, null, null);
    }

    public Route(String path, RouteHandler handler, String description, Topic topic) {
        this(path, handler, description, topic, null, null);
    }

    public Route(String path, RouteHandler handler, String description,
                 Topic topic, String fetchStrategy) {
        this(path, handler, description, topic, fetchStrategy, null);
    }

    public Route(String path, RouteHandler handler, String description,
                 Topic topic, String fetchStrategy, String fetchDetail) {
        this(path, handler, description, topic, fetchStrategy, fetchDetail, 0);
    }

    /** The topic's label, or null for routes without a topic. */
    public String category() {
        return topic == null ? null : topic.label();
    }
}
