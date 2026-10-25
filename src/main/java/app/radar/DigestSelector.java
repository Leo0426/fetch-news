package app.radar;

import app.radar.RadarTypes.Decision;
import app.radar.RadarTypes.Event;
import app.radar.RadarTypes.Selection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DigestSelector {
    private enum DefaultBucket {
        INSTANCE
    }

    public Selection select(List<Event> events, RadarConfig config) {
        List<Event> selected = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        Map<Object, Integer> counts = new HashMap<>();
        for (Event event : events.stream()
                .sorted(Comparator.comparing(
                        (Event e) -> e.primary().score(), Comparator.nullsLast(Comparator.reverseOrder())))
                .toList()) {
            var item = event.primary();
            Object group = DefaultBucket.INSTANCE;
            int limit = config.defaultGroupLimit();
            for (var entry : config.categoryGroups().entrySet()) {
                if (entry.getValue().categories().contains(item.category())) {
                    group = entry.getKey();
                    limit = entry.getValue().limit();
                    break;
                }
            }
            String reason;
            if (item.score() == null || item.error() != null) reason = "analysis_failed";
            else if (item.score() < config.settings(item.profile()).threshold()) reason = "below_threshold";
            else if (limit > 0 && counts.getOrDefault(group, 0) >= limit) reason = "category_limit";
            else if (selected.size() >= config.maxItems()) reason = "total_limit";
            else {
                reason = "selected";
                selected.add(event);
                counts.merge(group, 1, Integer::sum);
            }
            decisions.add(new Decision(item.link(), reason, event.id()));
            for (var related : event.related()) decisions.add(new Decision(related.link(), "duplicate", event.id()));
        }
        return new Selection(selected, decisions);
    }
}
