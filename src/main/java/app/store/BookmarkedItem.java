package app.store;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One row from the bookmarks table, enriched with display helpers.
 */
public record BookmarkedItem(
        String link,
        String title,
        String routePath,
        Instant bookmarkedAt) {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public String formattedDate() {
        return bookmarkedAt != null ? FMT.format(bookmarkedAt) : "—";
    }

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
        if (routePath == null) return "?";
        String[] parts = routePath.split("/");
        if (parts.length < 2) return routePath;
        return switch (parts[1]) {
            case "hn"            -> "HN";
            case "bbc"           -> "BBC";
            case "github"        -> "GitHub";
            case "reddit"        -> "Reddit";
            case "stackoverflow" -> "SO";
            case "arxiv"         -> "arXiv";
            case "sspai"         -> "少数派";
            case "36kr"          -> "36氪";
            case "xinhua"        -> "新华";
            case "huanqiu"       -> "环球";
            case "cctv"          -> "CCTV";
            case "bilibili"      -> "B站";
            default              -> parts[1];
        };
    }
}
