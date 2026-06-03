package app.core;

import java.time.Instant;
import java.util.List;

/**
 * Normalized item inside a feed.
 *
 * @param title       item title
 * @param link        canonical item link
 * @param description item description HTML
 * @param pubDate     publication date when provided by the source
 * @param author      item author when provided by the source
 * @param categories  item categories or tags
 */
public record FeedItem(
        String title,
        String link,
        String description,
        Instant pubDate,
        String author,
        List<String> categories) {
    /**
     * Normalizes null category lists to an immutable empty list.
     */
    public FeedItem {
        categories = List.copyOf(categories == null ? List.of() : categories);
    }
}
