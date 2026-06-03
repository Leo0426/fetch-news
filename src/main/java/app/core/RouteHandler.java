package app.core;

/**
 * Produces a normalized feed for a registered route.
 */
public interface RouteHandler {
    /**
     * Handles a route request and returns a normalized feed.
     *
     * @param context request context for the route
     * @return normalized feed
     * @throws Exception when fetching or parsing fails
     */
    Feed handle(RouteContext context) throws Exception;
}
