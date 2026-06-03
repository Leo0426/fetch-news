package app.core;

/**
 * Known route-layer error categories and their HTTP status codes.
 */
public enum RouteError {
    /**
     * No registered route matched the request path.
     */
    ROUTE_NOT_FOUND("route not found", 404),
    /**
     * An upstream HTTP request failed or returned an error status.
     */
    UPSTREAM_REQUEST_FAILED("upstream request failed", 502),
    /**
     * Upstream content was present but not usable by the route parser.
     */
    UPSTREAM_INVALID_CONTENT("upstream returned invalid content", 502),
    /**
     * A route parser failed while normalizing source content.
     */
    PARSER_FAILED("parser failed", 502),
    /**
     * A route produced no feed items.
     */
    EMPTY_FEED("route returned empty feed", 502),
    /**
     * The requested route is disabled by runtime configuration.
     */
    ROUTE_DISABLED("route disabled", 404),
    /**
     * Rendering RSS or JSON failed after route handling completed.
     */
    RENDERER_FAILED("renderer failed", 500),
    /**
     * A request parameter or persisted route setting is invalid.
     */
    INVALID_PARAMETER("invalid route parameter", 400);

    private final String message;
    private final int statusCode;

    /**
     * Creates an error category.
     *
     * @param message stable human-readable message
     * @param statusCode HTTP status code for the error
     */
    RouteError(String message, int statusCode) {
        this.message = message;
        this.statusCode = statusCode;
    }

    /**
     * Returns the stable error message.
     *
     * @return error message
     */
    public String message() {
        return message;
    }

    /**
     * Returns the HTTP status code associated with this error.
     *
     * @return HTTP status code
     */
    public int statusCode() {
        return statusCode;
    }
}
