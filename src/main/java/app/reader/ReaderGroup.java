package app.reader;

import app.core.Topic;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One source group on the reader page.
 *
 * @param name  human-readable source name (e.g. "HN · top", "BBC · technology")
 * @param tag   broad category tag shown as a chip (e.g. "时事要闻", "软件开发")
 * @param items feed items belonging to this source
 */
public record ReaderGroup(String name, String tag, List<ReaderItem> items) {
    private static final List<String> TOPIC_ORDER = Topic.labels();

    /**
     * Groups cards by their source route, keeping each group's card order. Groups follow the topic
     * display order (unknown topics after the known ones, untagged last); within a topic the source
     * that saw the newest article comes first.
     *
     * @param aliasOf a route path's configured alias, or null to use the derived source name
     * @param topicOf a route path's topic
     */
    public static List<ReaderGroup> bySource(List<ReaderItem> items, Function<String, String> aliasOf,
                                             Function<String, String> topicOf) {
        Map<String, List<ReaderItem>> byRoute = new LinkedHashMap<>();
        for (ReaderItem item : items) byRoute.computeIfAbsent(item.routePath(), k -> new ArrayList<>()).add(item);
        return byRoute.entrySet().stream()
                .map(entry -> {
                    String alias = aliasOf.apply(entry.getKey());
                    String name = alias != null ? alias : entry.getValue().getFirst().groupName();
                    return new ReaderGroup(name, topicOf.apply(entry.getKey()), entry.getValue());
                })
                .sorted(Comparator.comparingInt(ReaderGroup::topicRank)
                        .thenComparing(group -> group.items().getFirst().firstSeenAt(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private static int topicRank(ReaderGroup group) {
        if (group.tag() == null || group.tag().isBlank()) return TOPIC_ORDER.size() + 1;
        int index = TOPIC_ORDER.indexOf(group.tag());
        return index >= 0 ? index : TOPIC_ORDER.size();
    }
}
