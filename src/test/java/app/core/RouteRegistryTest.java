package app.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for explicit route registry matching.
 */
class RouteRegistryTest {
    /**
     * Verifies that parameterized route patterns capture path variables.
     */
    @Test
    void matchesStaticAndParameterizedPaths() throws Exception {
        RouteHandler handler = context -> new Feed("t", "https://example.com", "d", List.of());
        RouteRegistry registry = new RouteRegistry(List.of(new Route("/github/releases/:owner/:repo", handler)));

        RouteMatch match = registry.resolve("/github/releases/quarkusio/quarkus");

        assertEquals(handler, match.handler());
        assertEquals("quarkusio", match.context().pathParam("owner"));
        assertEquals("quarkus", match.context().pathParam("repo"));
    }

    /**
     * Verifies that unknown paths raise the normalized not-found error.
     */
    @Test
    void rejectsUnknownRoutes() {
        RouteRegistry registry = new RouteRegistry(List.of());

        RouteException error = assertThrows(RouteException.class, () -> registry.resolve("/missing"));

        assertEquals(RouteError.ROUTE_NOT_FOUND, error.error());
    }
}
