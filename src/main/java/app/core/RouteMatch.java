package app.core;

/**
 * Result of resolving a request path against the route registry.
 *
 * @param handler matched route handler
 * @param context route context containing path parameters
 */
public record RouteMatch(RouteHandler handler, RouteContext context) {}
