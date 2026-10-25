package app.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RouteTest {
    private static final RouteHandler HANDLER = context ->
            new Feed("News", "https://example.com", "News feed", List.of());

    @Test
    void existingConstructorsDoNotIntroduceRecommendedBackgroundWork() {
        List<Route> routes = List.of(
                new Route("/news", HANDLER),
                new Route("/news", HANDLER, "News"),
                new Route("/news", HANDLER, "News", Topic.SCIENCE),
                new Route("/news", HANDLER, "News", Topic.SCIENCE, "RSS"),
                new Route("/news", HANDLER, "News", Topic.SCIENCE, "RSS", "Official feed"));

        routes.forEach(route -> assertEquals(0, route.recommendedScheduleMinutes()));
    }

    @Test
    void rejectsNegativeRecommendedSchedule() {
        assertThrows(IllegalArgumentException.class,
                () -> new Route("/news", HANDLER, "News", Topic.SCIENCE, "RSS", "Official feed", -1));
    }
}
