package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReaderGroupTest {
    @Test
    void groupsBySourceNamesByAliasOrRouteAndPutsTheNewestSourceFirstWithinATopic() {
        Instant now = Instant.parse("2026-10-24T08:00:00Z");
        var items = List.of(
                card("/bbc/news/world", "https://a", now.minusSeconds(60)),
                card("/hn/top", "https://b", now),
                card("/bbc/news/world", "https://c", now.minusSeconds(120)),
                card("/undated", "https://d", null));

        var groups = ReaderGroup.bySource(items, Map.of("/bbc/news/world", "BBC全球")::get,
                path -> "科技资讯");

        assertEquals(List.of(items.get(1).groupName(), "BBC全球", items.get(3).groupName()),
                groups.stream().map(ReaderGroup::name).toList(), "alias wins; undated sources go last");
        assertEquals(List.of("https://a", "https://c"), groups.get(1).items().stream().map(ReaderItem::link).toList());
        assertEquals("科技资讯", groups.get(1).tag());
    }

    @Test
    void ordersGroupsByTopicDisplayOrderThenByNewestSource() {
        Instant now = Instant.parse("2026-10-24T08:00:00Z");
        Map<String, String> topics = Map.of(
                "/hn/top", "科技资讯", "/ars/latest", "科技资讯", "/bbc/news/world", "时事要闻",
                "/openai/news", "AI", "/custom/feed", "自定义");
        var items = List.of(
                card("/hn/top", "https://a", now.minusSeconds(60)),
                card("/untagged", "https://b", now),
                card("/custom/feed", "https://c", now),
                card("/ars/latest", "https://d", now),
                card("/openai/news", "https://e", now.minusSeconds(600)),
                card("/bbc/news/world", "https://f", now.minusSeconds(3600)));

        var groups = ReaderGroup.bySource(items, path -> null, topics::get);

        assertEquals(List.of("/bbc/news/world", "/openai/news", "/ars/latest", "/hn/top", "/custom/feed", "/untagged"),
                groups.stream().map(group -> group.items().getFirst().routePath()).toList());
    }

    private static ReaderItem card(String route, String link, Instant firstSeen) {
        return new ReaderItem(route, "Title", link, null, firstSeen, null, null);
    }
}
