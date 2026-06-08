package app.reader;

import java.time.Duration;
import java.time.Instant;

/**
 * A feed item enriched with reader-friendly display fields.
 * {@code summary} is {@code null} when no AI summary has been generated yet.
 */
public record ReaderItem(
        String routePath,
        String title,
        String link,
        Instant firstSeenAt,
        String summary) {

    /** Short source label derived from the route path's first segment. */
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
            case "cnblogs"       -> "博客园";
            case "microsoft"     -> "MSDN";
            case "techcrunch"    -> "TC";
            case "wired"         -> "Wired";
            case "producthunt"   -> "PH";
            case "mritd"         -> "mritd";
            case "diygod"        -> "DIYGod";
            default              -> parts[1];
        };
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
}
