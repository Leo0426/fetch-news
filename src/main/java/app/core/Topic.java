package app.core;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The topics sources are grouped by in the reader, triage and Feishu. Labels are what users see and
 * what route-config.json and feishu-config.json store.
 */
public enum Topic {
    NEWS("时事要闻"),
    AI("AI"),
    TECH("科技资讯"),
    SOFTWARE("软件开发"),
    SECURITY("网络安全"),
    BUSINESS("财经商业"),
    SCIENCE("科学"),
    HEALTH("健康医学"),
    HUMANITIES("人文社会"),
    DESIGN("设计艺术"),
    GAMES("游戏"),
    FOLLOWING("关注动态"),
    GENERAL("通用");

    /**
     * Categories renamed when sources were regrouped by topic, mapped to the topics that took over
     * their sources. Settings keyed by an old name (e.g. Feishu push rules) are carried over on load.
     */
    public static final Map<String, List<Topic>> RENAMED = Map.ofEntries(
            Map.entry("GitHub 项目", List.of(SOFTWARE, AI)),
            Map.entry("社会新闻", List.of(NEWS)),
            Map.entry("科学探索", List.of(SCIENCE)),
            Map.entry("气候环境", List.of(SCIENCE)),
            Map.entry("软件工程", List.of(SOFTWARE)),
            Map.entry("商业经济", List.of(BUSINESS)),
            Map.entry("设计文化", List.of(DESIGN, HUMANITIES)),
            Map.entry("科技精选", List.of(TECH)),
            Map.entry("心理认知", List.of(HUMANITIES, SCIENCE)),
            Map.entry("教育学习", List.of(HUMANITIES)),
            Map.entry("数据洞察", List.of(HUMANITIES)),
            Map.entry("硬件创客", List.of(TECH)),
            Map.entry("游戏开发", List.of(GAMES)),
            Map.entry("人文历史", List.of(HUMANITIES)),
            Map.entry("艺术影像", List.of(DESIGN)),
            Map.entry("城市建筑", List.of(DESIGN)),
            Map.entry("游戏文化", List.of(GAMES)),
            Map.entry("技术博客", List.of(TECH, SOFTWARE)),
            Map.entry("编程", List.of(SOFTWARE, TECH)),
            Map.entry("学术", List.of(SCIENCE)),
            Map.entry("英文媒体", List.of(NEWS, HUMANITIES)),
            Map.entry("中文媒体", List.of(TECH, NEWS)),
            Map.entry("TG 科技", List.of(TECH, FOLLOWING)),
            Map.entry("X 科技", List.of(AI, SOFTWARE)),
            Map.entry("社区", List.of(FOLLOWING, TECH)),
            Map.entry("视频", List.of(FOLLOWING)));

    private final String label;

    Topic(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** All labels in display order. */
    public static List<String> labels() {
        return Arrays.stream(values()).map(Topic::label).toList();
    }
}
