package app.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The unread inbox: each reader's retained unread articles, filtered and paged by insertion order.
 */
public final class UnreadInbox {
    private final FeedStore store;

    UnreadInbox(FeedStore store) {
        this.store = store;
    }

    private static final String UNREAD_CONDITION = """
            trim(fi.title, char(9, 10, 11, 12, 13, 32)) <> ''
            AND trim(fi.item_link, char(9, 10, 11, 12, 13, 32)) <> ''
            AND NOT EXISTS (SELECT 1 FROM read_items r WHERE r.user_id = %d AND r.item_link = fi.item_link)
            """;

    public UnreadPage loadUnreadPage(
            long userId, String source, String query, boolean bookmarkedOnly, Long beforeId, int limit)
            throws SQLException {
        return store.read(conn -> {
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("Unread page limit must be between 1 and 100");
            }
            if (beforeId != null && beforeId <= 0) {
                throw new IllegalArgumentException("Unread page cursor must be positive");
            }
            String normalizedSource = source == null ? "" : source.strip();
            String normalizedQuery = query == null ? "" : query.strip();
            StringBuilder where = new StringBuilder(UNREAD_CONDITION.formatted(userId));
            List<String> parameters = new ArrayList<>();
            if (!normalizedSource.isEmpty()) {
                where.append(" AND fi.route_path = ?");
                parameters.add(normalizedSource);
            }
            if (!normalizedQuery.isEmpty()) {
                where.append(" AND (" + SearchIndex.containsTerm("fi.title", normalizedQuery)
                        + " OR " + SearchIndex.containsTerm("fi.description", normalizedQuery)
                        + " OR EXISTS (SELECT 1 FROM ai_summaries s WHERE s.item_link = fi.item_link AND "
                        + SearchIndex.containsTerm("s.summary", normalizedQuery) + "))\n");
                parameters.add(normalizedQuery);
                parameters.add(normalizedQuery);
                parameters.add(normalizedQuery);
            }
            if (bookmarkedOnly) {
                where.append(" AND EXISTS (SELECT 1 FROM bookmarks b WHERE b.user_id = " + userId
                        + " AND b.item_link = fi.item_link)");
            }

            long totalCount = countUnreadItems(conn, where.toString(), parameters);
            List<String> sources = loadUnreadSources(conn, userId);
            String cursorClause = beforeId == null ? "" : " AND fi.id < ?";
            List<StoredItem> items = new ArrayList<>();
            List<String> bookmarkedLinks = new ArrayList<>();
            Long lastId = null;
            boolean hasMore = false;
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT fi.id, %s,
                           EXISTS (SELECT 1 FROM bookmarks b WHERE b.user_id = ? AND b.item_link = fi.item_link)
                               AS bookmarked
                    FROM feed_items fi WHERE
                    """.formatted(FeedStore.itemColumns("fi.")) + where + cursorClause + " ORDER BY fi.id DESC LIMIT ?")) {
                ps.setLong(1, userId);
                int index = bindUnreadParameters(ps, parameters, 2);
                if (beforeId != null) ps.setLong(index++, beforeId);
                ps.setInt(index, limit + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (items.size() == limit) {
                            hasMore = true;
                            break;
                        }
                        items.add(FeedStore.readStoredItem(rs));
                        if (rs.getBoolean("bookmarked")) bookmarkedLinks.add(rs.getString("item_link"));
                        lastId = rs.getLong("id");
                    }
                }
            }
            return new UnreadPage(items, bookmarkedLinks, totalCount, hasMore ? lastId : null, sources);
        });
    }

    private static long countUnreadItems(Connection conn, String where, List<String> parameters)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM feed_items fi WHERE " + where)) {
            bindUnreadParameters(ps, parameters, 1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static List<String> loadUnreadSources(Connection conn, long userId) throws SQLException {
        List<String> sources = new ArrayList<>();
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT DISTINCT fi.route_path FROM feed_items fi WHERE "
                     + UNREAD_CONDITION.formatted(userId) + " ORDER BY fi.route_path")) {
            while (rs.next()) sources.add(rs.getString("route_path"));
        }
        return sources;
    }

    private static int bindUnreadParameters(PreparedStatement ps, List<String> parameters, int first)
            throws SQLException {
        for (int i = 0; i < parameters.size(); i++) ps.setString(first + i, parameters.get(i));
        return first + parameters.size();
    }
}
