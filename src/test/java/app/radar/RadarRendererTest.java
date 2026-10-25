package app.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RadarRendererTest {
    @Test
    void exportIsDeterministicAndEscapesModelMarkupAndUnsafeLinks() {
        var analysis = EventDeduplicatorTest.analysis("javascript:alert(1)", "tech-news", 8);
        var report = new RadarTypes.Report(
                "id",
                "2026-10-19",
                "Asia/Shanghai",
                List.of(new RadarTypes.ReportStory(
                        new RadarTypes.Event("event", analysis, List.of()),
                        Map.of("summary", List.of("<script>alert(1)</script> [click](javascript:x)")),
                        null)),
                List.of(),
                1,
                0,
                0,
                Map.of());
        var renderer = new RadarRenderer();
        String output = renderer.markdown(report);
        assertEquals(output, renderer.markdown(report));
        assertFalse(output.contains("<script>"));
        assertFalse(output.contains("](javascript:"));
        assertTrue(output.contains("AI"));
    }

    @Test
    void preservesConfiguredBlockOrderAndUsesOriginalHeadlines() {
        var analysis = EventDeduplicatorTest.analysis("https://a.test", "tech-blog", 8);
        var blocks = new LinkedHashMap<String, List<String>>();
        blocks.put("background", List.of("First"));
        blocks.put("approach", List.of("Second"));
        blocks.put("insights", List.of("Third"));
        var story = new RadarTypes.ReportStory(new RadarTypes.Event("a", analysis, List.of()), blocks, null);
        assertEquals(
                List.of("background", "approach", "insights"),
                new ArrayList<>(story.blocks().keySet()));
        var report = new RadarTypes.Report(
                "id",
                "2026-10-19",
                "Asia/Shanghai",
                List.of(story),
                List.of(),
                1,
                0,
                0,
                Map.of(),
                Map.of(analysis.link(), "Original headline"));
        String text = new RadarRenderer().markdown(report);
        assertTrue(text.contains("Original headline"));
        assertTrue(text.indexOf("First") < text.indexOf("Second"));
        assertTrue(text.indexOf("Second") < text.indexOf("Third"));
    }
}
