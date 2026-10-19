package app.reader;

import app.radar.RadarRenderer;
import app.radar.RadarTypes.Report;
import app.radar.RadarTypes.RunStatus;
import java.util.Map;

public record RadarPageView(String date,String zoneId,boolean enabled,String requestKey,
                            Report report,RunStatus latest,RunStatus saved,Map<String,String> titles) {
    public RadarPageView { titles=Map.copyOf(titles); }
    public java.util.List<String> profiles() { return report==null ? java.util.List.of() : report.stories().stream().map(s->s.event().primary().profile()).distinct().sorted().toList(); }
    public String title(String link) { return titles.getOrDefault(link,link); }
    public String safeUrl(String link) { return RadarRenderer.safeUrl(link); }
    public String stateLabel(String state) {
        return switch(state) {
            case "queued" -> "排队中"; case "running" -> "生成中"; case "paused" -> "已暂停";
            case "success" -> "已完成"; case "partial" -> "部分完成"; case "failed" -> "生成失败"; default -> state;
        };
    }
}
