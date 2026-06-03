package app.store;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One row from feed_items, enriched with storage timestamps for the admin UI.
 */
public record StoredItem(
        String routePath,
        String title,
        String link,
        String author,
        Instant pubDate,
        Instant firstSeenAt) {

    private static final DateTimeFormatter SHORT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public String formattedPubDate() {
        return pubDate != null ? SHORT.format(pubDate) : "—";
    }

    public String formattedFirstSeen() {
        return SHORT.format(firstSeenAt);
    }
}
