package app.reader;

import app.store.SummaryStatus;
import app.support.SourceLabels;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * A feed item enriched with reader-friendly display fields.
 * {@code summary} is {@code null} when no AI summary has been generated yet.
 */
public record ReaderItem(
        String routePath,
        String title,
        String link,
        Instant pubDate,
        Instant firstSeenAt,
        String summary,
        SummaryStatus summaryStatus) {

    private static final DateTimeFormatter SHORT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Short source label derived from the route path's first segment. */
    public String sourceLabel() {
        return SourceLabels.shortName(routePath);
    }

    /** Human-readable age relative to now (e.g. "3 小时前"). */
    public String formattedAge() {
        if (firstSeenAt == null) return "—";
        long minutes = Duration.between(firstSeenAt, Instant.now()).toMinutes();
        if (minutes < 1)  return "刚刚";
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24)   return hours + " 小时前";
        return hours / 24 + " 天前";
    }

    public String formattedFetchedAt() {
        return firstSeenAt != null ? SHORT.format(firstSeenAt) : "—";
    }

    public String formattedPublishedAt() {
        return pubDate != null ? SHORT.format(pubDate) : "—";
    }

    /**
     * Human-readable source group name derived from the route path.
     * Examples: "/hn/top" → "HN · top", "/bbc/news/technology" → "BBC · technology".
     */
    public String groupName() {
        if (routePath == null) return "其他";
        String[] parts = routePath.split("/");
        String label = sourceLabel();
        if (parts.length <= 2) return label;
        // Use the last path segment as qualifier
        return label + " · " + parts[parts.length - 1];
    }

    /** Whether this item has an AI-generated summary. */
    public boolean hasSummary() {
        return summary != null && !summary.isBlank();
    }

    public boolean hasSummaryStatus() {
        return summaryStatus != null;
    }
}
