package app.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;

/**
 * Read-only article queries behind the reader, briefing, digests and admin item browsing.
 */
public final class ItemQueries {
    private final FeedStore store;

    ItemQueries(FeedStore store) {
        this.store = store;
    }

    /**
     * Returns items for {@code routePath} whose {@code first_seen_at} is at or after {@code since},
     * newest first. Used to identify items discovered in a single scheduled fetch.
     */
    public List<StoredItem> loadNewItemsSince(String routePath, Instant since) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(FeedStore.selectItems("""
                    FROM feed_items
                    WHERE route_path = ? AND first_seen_at >= ?
                    """))) {
                ps.setString(1, routePath);
                ps.setString(2, firstSeenLowerBound(since));
                try (ResultSet rs = ps.executeQuery()) {
                    return readNewItemsSince(rs, since);
                }
            }
        }
    }

    /**
     * Returns items across multiple routes whose {@code first_seen_at} is at or after
     * {@code since}, newest first. Used for category-level digest messages.
     */
    public List<StoredItem> loadNewItemsSinceForRoutes(List<String> routePaths, Instant since)
            throws SQLException {
        synchronized (store) {
            if (routePaths.isEmpty()) return List.of();
            String placeholders = String.join(",", Collections.nCopies(routePaths.size(), "?"));
            try (PreparedStatement ps = store.conn.prepareStatement(FeedStore.selectItems("""
                    FROM feed_items
                    WHERE route_path IN (%s) AND first_seen_at >= ?
                    """.formatted(placeholders)))) {
                for (int i = 0; i < routePaths.size(); i++) ps.setString(i + 1, routePaths.get(i));
                ps.setString(routePaths.size() + 1, firstSeenLowerBound(since));
                try (ResultSet rs = ps.executeQuery()) {
                    return readNewItemsSince(rs, since);
                }
            }
        }
    }

    private static String firstSeenLowerBound(Instant since) {
        // Instant text has variable fractional precision, so SQL includes the entire boundary second.
        String second = since.truncatedTo(ChronoUnit.SECONDS).toString();
        return second.substring(0, second.length() - 1);
    }

    private static List<StoredItem> readNewItemsSince(ResultSet rs, Instant since) throws SQLException {
        List<StoredItem> result = new ArrayList<>();
        while (rs.next()) {
            StoredItem item = FeedStore.readStoredItem(rs);
            if (!item.firstSeenAt().isBefore(since)) result.add(item);
        }
        result.sort(Comparator.comparing(StoredItem::firstSeenAt).reversed());
        return result;
    }

    /**
     * Returns all items first seen within {@code window} of now, newest first.
     * Capped at 500 rows so the reader stays fast regardless of volume.
     */
    public List<StoredItem> loadRecentItems(Duration window) throws SQLException {
        synchronized (store) {
            String since = Instant.now().minus(window).toString();
            List<StoredItem> result = new ArrayList<>();
            try (PreparedStatement ps = store.conn.prepareStatement(FeedStore.selectItems("""
                    FROM feed_items
                    WHERE first_seen_at >= ?
                    ORDER BY first_seen_at DESC
                    LIMIT 500
                    """))) {
                ps.setString(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(FeedStore.readStoredItem(rs));
                }
            }
            return result;
        }
    }

    /**
     * Returns items whose {@code first_seen_at} falls within the given calendar date
     * (system default timezone), ordered newest-first.
     */
    public List<StoredItem> loadItemsForDate(LocalDate date) throws SQLException {
        synchronized (store) {
            ZoneId tz = ZoneId.systemDefault();
            String from = date.atStartOfDay(tz).toInstant().toString();
            String to   = date.plusDays(1).atStartOfDay(tz).toInstant().toString();
            List<StoredItem> result = new ArrayList<>();
            try (PreparedStatement ps = store.conn.prepareStatement(FeedStore.selectItems("""
                    FROM feed_items
                    WHERE first_seen_at >= ? AND first_seen_at < ?
                    ORDER BY first_seen_at DESC
                    LIMIT 1000
                    """))) {
                ps.setString(1, from);
                ps.setString(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(FeedStore.readStoredItem(rs));
                }
            }
            return result;
        }
    }

    /**
     * Returns the total number of stored items for a route.
     * Parameterized patterns like {@code /hn/:feed} are matched via SQL LIKE.
     */
    public int countItems(String routePath) throws SQLException {
        synchronized (store) {
            String likePattern = routePath.replaceAll("/:[^/]+", "/%");
            boolean exact = likePattern.equals(routePath);
            String sql = exact
                    ? "SELECT COUNT(*) FROM feed_items WHERE route_path=?"
                    : "SELECT COUNT(*) FROM feed_items WHERE route_path LIKE ?";
            try (PreparedStatement ps = store.conn.prepareStatement(sql)) {
                ps.setString(1, exact ? routePath : likePattern);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    /**
     * Returns a page of stored items for a route, newest first.
     * Parameterized patterns like {@code /hn/:feed} are matched via SQL LIKE.
     */
    public List<StoredItem> browseItems(String routePath, int offset, int limit) throws SQLException {
        synchronized (store) {
            String likePattern = routePath.replaceAll("/:[^/]+", "/%");
            boolean exact = likePattern.equals(routePath);
            String sql = FeedStore.selectItems("FROM feed_items WHERE route_path " + (exact ? "= ?" : "LIKE ?")
                    + " ORDER BY first_seen_at DESC LIMIT ? OFFSET ?");
            List<StoredItem> result = new ArrayList<>();
            try (PreparedStatement ps = store.conn.prepareStatement(sql)) {
                ps.setString(1, exact ? routePath : likePattern);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(FeedStore.readStoredItem(rs));
                }
            }
            return result;
        }
    }

    /**
     * Returns plain-text description excerpts for links whose source description is at least
     * {@link #EXCERPT_MIN_LENGTH} characters — long enough to read without an AI summary.
     */
    public Map<String, String> loadExcerpts(List<String> links) throws SQLException {
        return loadDescriptionTexts(links, FeedStore.EXCERPT_MIN_LENGTH, FeedStore.EXCERPT_MAX_LENGTH);
    }

    /** Returns plain-text descriptions of at least {@code minLength} code points, cut to {@code maxLength}. */
    public Map<String, String> loadDescriptionTexts(List<String> links, int minLength,
                                                                          int maxLength) throws SQLException {
        synchronized (store) {
            Map<String, String> result = new HashMap<>();
            if (links.isEmpty()) return result;
            String placeholders = String.join(",", Collections.nCopies(links.size(), "?"));
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT item_link, description FROM feed_items WHERE item_link IN (" + placeholders + ")"
                            + " AND length(description) >= " + minLength)) {
                for (int i = 0; i < links.size(); i++) ps.setString(i + 1, links.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String text = Jsoup.parse(rs.getString("description")).text().strip();
                        int length = text.codePointCount(0, text.length());
                        if (length < Math.max(minLength, 1)) continue;
                        result.put(rs.getString("item_link"),
                                text.substring(0, text.offsetByCodePoints(0, Math.min(maxLength, length))));
                    }
                }
            }
            return result;
        }
    }
}
