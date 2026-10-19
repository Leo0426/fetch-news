package app.radar;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RadarRendererTest {
    @Test void exportIsDeterministicAndEscapesModelMarkupAndUnsafeLinks() {
        var analysis=EventDeduplicatorTest.analysis("javascript:alert(1)","tech-news",8);
        var report=new RadarTypes.Report("id","2026-10-19","Asia/Shanghai",List.of(new RadarTypes.ReportStory(new RadarTypes.Event("event",analysis,List.of()),Map.of("summary",List.of("<script>alert(1)</script> [click](javascript:x)")),null)),List.of(),1,0,0,Map.of());
        var renderer=new RadarRenderer();
        String output=renderer.markdown(report);
        assertEquals(output,renderer.markdown(report));
        assertFalse(output.contains("<script>"));
        assertFalse(output.contains("](javascript:"));
        assertTrue(output.contains("AI"));
    }
}
