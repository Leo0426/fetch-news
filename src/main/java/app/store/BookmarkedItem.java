package app.store;

import app.support.SourceLabels;
import java.time.Duration;
import java.time.Instant;

/**
 * One row from the bookmarks table, enriched with display helpers.
 */
public record BookmarkedItem(
        String link,
        String title,
        String routePath,
        Instant bookmarkedAt) {

    public String formattedAge() {
        if (bookmarkedAt == null) return "—";
        long minutes = Duration.between(bookmarkedAt, Instant.now()).toMinutes();
        if (minutes < 1)  return "刚刚";
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24)   return hours + " 小时前";
        return hours / 24 + " 天前";
    }

    public String sourceLabel() {
        return SourceLabels.shortName(routePath);
    }
}
