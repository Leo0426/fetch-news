package app.core;

import java.util.List;

/**
 * Normalized feed returned by every route before rendering.
 *
 * @param title feed title
 * @param link canonical feed source link
 * @param description short feed description
 * @param items feed items in display order
 */
public record Feed(String title, String link, String description, List<FeedItem> items) {
    /**
     * Normalizes null item lists to an immutable empty list.
     */
    public Feed {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
