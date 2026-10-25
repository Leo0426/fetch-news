package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReaderGroupTest {
    @Test
    void groupsBySourceNamesByAliasOrRouteAndPutsTheNewestSourceFirst() {
        Instant now = Instant.parse("2026-10-24T08:00:00Z");
        var items = List.of(
                card("/bbc/news/world", "https://a", now.minusSeconds(60)),
                card("/hn/top", "https://b", now),
                card("/bbc/news/world", "https://c", now.minusSeconds(120)),
                card("/undated", "https://d", null));

        var groups = ReaderGroup.bySource(items, Map.of("/bbc/news/world", "BBC全球")::get,
                path -> path.startsWith("/bbc") ? "时事要闻" : "科技资讯");

        assertEquals(List.of(items.get(1).groupName(), "BBC全球", items.get(3).groupName()),
                groups.stream().map(ReaderGroup::name).toList(), "alias wins; undated sources go last");
        assertEquals(List.of("https://a", "https://c"), groups.get(1).items().stream().map(ReaderItem::link).toList());
        assertEquals("时事要闻", groups.get(1).tag());
        assertEquals("科技资讯", groups.get(0).tag());
    }

    private static ReaderItem card(String route, String link, Instant firstSeen) {
        return new ReaderItem(route, "Title", link, null, firstSeen, null, null);
    }
}
