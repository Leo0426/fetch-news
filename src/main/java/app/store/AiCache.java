package app.store;

import app.core.FeedItem;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cached AI output per article: generated summaries and extracted article text.
 */
public final class AiCache {
    private final FeedStore store;

    AiCache(FeedStore store) {
        this.store = store;
    }

    /**
     * Returns a new list where each item's description is replaced by its AI extract
     * when one exists. Items without a matching extract are returned unchanged.
     * Uses a single batch SELECT for efficiency.
     */
    public List<FeedItem> mergeExtracts(List<FeedItem> items) throws SQLException {
        synchronized (store) {
            if (items.isEmpty()) return items;
            List<String> links = items.stream()
                    .filter(i -> i.link() != null && !i.link().isBlank())
                    .map(FeedItem::link)
                    .toList();
            if (links.isEmpty()) return items;
            String placeholders = String.join(",", Collections.nCopies(links.size(), "?"));
            Map<String, String> extracts = new HashMap<>();
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT item_link, content FROM ai_extracts WHERE item_link IN (" + placeholders + ")")) {
                for (int i = 0; i < links.size(); i++) ps.setString(i + 1, links.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) extracts.put(rs.getString("item_link"), rs.getString("content"));
                }
            }
            if (extracts.isEmpty()) return items;
            return items.stream()
                    .map(item -> {
                        String extract = item.link() != null ? extracts.get(item.link()) : null;
                        return extract != null
                                ? new FeedItem(item.title(), item.link(), extract,
                                               item.pubDate(), item.author(), item.categories())
                                : item;
                    })
                    .toList();
        }
    }

    /**
     * Returns the AI-extracted content for an article URL, or {@code null} if not cached.
     */
    public String loadExtract(String itemLink) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT content FROM ai_extracts WHERE item_link=?")) {
                ps.setString(1, itemLink);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString("content") : null;
                }
            }
        }
    }

    /** Returns the cached summary for an article URL, or {@code null} if not cached. */
    public String loadSummary(String itemLink) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT summary FROM ai_summaries WHERE item_link=?")) {
                ps.setString(1, itemLink);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString("summary") : null;
                }
            }
        }
    }

    /**
     * Batch-loads summaries for a list of links.
     * Returns a map of {@code itemLink -> summary} for links that have a cached summary.
     */
    public Map<String, String> loadSummaries(List<String> links) throws SQLException {
        synchronized (store) {
            Map<String, String> result = new HashMap<>();
            if (links.isEmpty()) return result;
            String placeholders = String.join(",", Collections.nCopies(links.size(), "?"));
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT item_link, summary FROM ai_summaries WHERE item_link IN (" + placeholders + ")")) {
                for (int i = 0; i < links.size(); i++) ps.setString(i + 1, links.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.put(rs.getString("item_link"), rs.getString("summary"));
                }
            }
            return result;
        }
    }

    /** Upserts an AI-generated summary for an article URL. */
    public void saveSummary(String itemLink, String summary, String model) throws SQLException {
        synchronized (store) {
            store.inTransaction(() -> {
                String now = Instant.now().toString();
                try (PreparedStatement ps = store.conn.prepareStatement("""
                        INSERT INTO ai_summaries (item_link, summary, model, created_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(item_link) DO UPDATE SET
                            summary    = excluded.summary,
                            model      = excluded.model,
                            created_at = excluded.created_at
                        """)) {
                    ps.setString(1, itemLink);
                    ps.setString(2, summary);
                    ps.setString(3, model);
                    ps.setString(4, now);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = store.conn.prepareStatement("""
                        INSERT OR IGNORE INTO ai_summary_runs
                            (item_link, status, started_at, completed_at, duration_ms, model, error_detail)
                        VALUES (?, 'success', ?, ?, 0, ?, NULL)
                        """)) {
                    ps.setString(1, itemLink);
                    ps.setString(2, now);
                    ps.setString(3, now);
                    ps.setString(4, model);
                    ps.executeUpdate();
                }
                store.searchIndex().refreshSearchIndex(List.of(itemLink));
                return null;
            });
        }
    }

    /** Returns the total number of cached AI summaries. */
    public int countSummaries() throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("SELECT COUNT(*) FROM ai_summaries");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Returns the total number of cached AI extracts. */
    public int countExtracts() throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("SELECT COUNT(*) FROM ai_extracts");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Upserts the AI-extracted content for an article URL.
     */
    public void saveExtract(String itemLink, String content, String model) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    INSERT INTO ai_extracts (item_link, content, model, created_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(item_link) DO UPDATE SET
                        content    = excluded.content,
                        model      = excluded.model,
                        created_at = excluded.created_at
                    """)) {
                ps.setString(1, itemLink);
                ps.setString(2, content);
                ps.setString(3, model);
                ps.setString(4, Instant.now().toString());
                ps.executeUpdate();
            }
        }
    }
}
