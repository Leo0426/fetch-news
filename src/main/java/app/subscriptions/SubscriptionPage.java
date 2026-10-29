package app.subscriptions;

import app.core.RouteConfig;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

public record SubscriptionPage(List<Source> sources, List<String> categories, String q,
                               String category, String filter, int totalCount, int subscribedCount) {
    public record Source(String path, String name, String description, String category, RouteConfig config,
                         int recommendedMinutes, Status status, int articleCount) {
        public boolean subscribed() { return config.enabled() && config.hasSchedule(); }
        public String cardId() {
            return "subscription-" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(path.getBytes(StandardCharsets.UTF_8));
        }
        public String cardUrl() { return "/index/subscriptions/card?path=" + encodedPath(); }
        public String editUrl() { return "/index/routes/edit?path=" + encodedPath(); }
        public String scheduleLabel() {
            if (config.scheduleCron() != null) return "自定义计划：" + config.scheduleCron();
            return config.scheduleMinutes() > 0 ? frequencyLabel(config.scheduleMinutes()) : "尚未设置自动更新";
        }
        public List<Frequency> frequencies() {
            return Stream.of(recommendedMinutes, 60, 180, 360, 1440).distinct().sorted()
                    .map(minutes -> new Frequency(minutes, frequencyLabel(minutes)
                            + (minutes == recommendedMinutes ? "（推荐）" : ""))).toList();
        }
        private String encodedPath() { return URLEncoder.encode(path, StandardCharsets.UTF_8); }
    }

    public record Status(String kind, String title, String detail, String checkedAt, boolean running) {}
    public record Frequency(int minutes, String label) {}

    private static String frequencyLabel(int minutes) {
        if (minutes % 1440 == 0) return "每 " + minutes / 1440 + " 天更新";
        if (minutes % 60 == 0) return "每 " + minutes / 60 + " 小时更新";
        return "每 " + minutes + " 分钟更新";
    }
}
