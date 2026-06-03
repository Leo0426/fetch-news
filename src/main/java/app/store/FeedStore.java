package app.store;

import app.core.Feed;
import app.core.FeedItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite-backed store for feed items and fetch logs.
 *
 * <p>All write methods are synchronized to handle concurrent requests safely with a single
 * connection. SQLite WAL mode allows concurrent reads alongside the single writer.
 */
public class FeedStore implements Closeable {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private final Connection conn;
    private final ObjectMapper mapper;

    public FeedStore(Path dbPath, ObjectMapper mapper) throws SQLException {
        this.mapper = mapper;
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        init();
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    private void init() throws SQLException {
        // busy_timeout must be the very first operation so any transient WAL/journal
        // lock is retried rather than immediately failing startup.
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
        }
        // journal_mode=WAL returns a result row ("wal") that must be consumed and
        // fully closed before any subsequent PreparedStatement is created; leaving
        // the result set open on the same connection causes SQLITE_BUSY on the next
        // prepare call when sqlite-jdbc tries to ensure autocommit state.
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA journal_mode=WAL")) {
            // result is "wal" — no action needed, just consume it
        }
        // Migration check must run before CREATE TABLE IF NOT EXISTS so that the
        // old schema (UNIQUE on route_path+item_link) is replaced before any new
        // rows are written under the new global-dedup constraint.
        if (hasOldFeedItemsSchema()) {
            try (Statement s = conn.createStatement()) {
                migrateToGlobalLinkDedup(s);
            }
        }
        try (Statement s = conn.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS feed_items (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        route_path    TEXT    NOT NULL,
                        item_link     TEXT    NOT NULL,
                        title         TEXT,
                        description   TEXT,
                        author        TEXT,
                        pub_date      TEXT,
                        categories    TEXT,
                        first_seen_at TEXT    NOT NULL,
                        last_seen_at  TEXT    NOT NULL,
                        UNIQUE(item_link)
                    )""");
            s.execute("""
                    CREATE INDEX IF NOT EXISTS idx_feed_items_route
                        ON feed_items(route_path, last_seen_at DESC)""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fetch_log (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        route_path     TEXT    NOT NULL,
                        fetched_at     TEXT    NOT NULL,
                        duration_ms    INTEGER NOT NULL,
                        item_count     INTEGER NOT NULL,
                        new_item_count INTEGER NOT NULL,
                        status         TEXT    NOT NULL,
                        error_detail   TEXT
                    )""");
            s.execute("""
                    CREATE INDEX IF NOT EXISTS idx_fetch_log_route
                        ON fetch_log(route_path, fetched_at DESC)""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS ai_extracts (
                        item_link  TEXT PRIMARY KEY,
                        content    TEXT NOT NULL,
                        model      TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS ai_summaries (
                        item_link  TEXT PRIMARY KEY,
                        summary    TEXT NOT NULL,
                        model      TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS bookmarks (
                        item_link     TEXT PRIMARY KEY,
                        title         TEXT,
                        route_path    TEXT,
                        bookmarked_at TEXT NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS read_items (
                        item_link TEXT PRIMARY KEY,
                        read_at   TEXT NOT NULL
                    )""");
            // Prune read state for items no longer in the feed store (keeps table small)
            s.execute("""
                    DELETE FROM read_items
                    WHERE item_link NOT IN (SELECT item_link FROM feed_items)""");
        }
    }

    private boolean hasOldFeedItemsSchema() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='feed_items'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String sql = rs.getString(1);
                return sql != null && sql.contains("UNIQUE(route_path");
            }
        }
        return false;
    }

    private void migrateToGlobalLinkDedup(Statement s) throws SQLException {
        conn.setAutoCommit(false);
        try {
            s.execute("DROP TABLE IF EXISTS feed_items_v2");
            s.execute("""
                    CREATE TABLE feed_items_v2 (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        route_path    TEXT    NOT NULL,
                        item_link     TEXT    NOT NULL,
                        title         TEXT,
                        description   TEXT,
                        author        TEXT,
                        pub_date      TEXT,
                        categories    TEXT,
                        first_seen_at TEXT    NOT NULL,
                        last_seen_at  TEXT    NOT NULL,
                        UNIQUE(item_link)
                    )""");
            // Keep the earliest-seen row per item_link; update last_seen_at to the global max.
            s.execute("""
                    INSERT INTO feed_items_v2
                        (route_path,item_link,title,description,author,pub_date,
                         categories,first_seen_at,last_seen_at)
                    SELECT fi.route_path, fi.item_link, fi.title, fi.description, fi.author,
                           fi.pub_date, fi.categories, fi.first_seen_at,
                           (SELECT MAX(last_seen_at) FROM feed_items WHERE item_link = fi.item_link)
                    FROM feed_items fi
                    WHERE fi.id = (SELECT MIN(id) FROM feed_items WHERE item_link = fi.item_link)
                    """);
            s.execute("DROP TABLE feed_items");
            s.execute("ALTER TABLE feed_items_v2 RENAME TO feed_items");
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    // ── feed items ────────────────────────────────────────────────────────────

    /**
     * Upserts all items for a route and returns the count of newly inserted items.
     */
    public synchronized int saveItems(String routePath, List<FeedItem> items) throws SQLException {
        List<FeedItem> valid = items.stream()
                .filter(i -> i.link() != null && !i.link().isBlank())
                .toList();
        if (valid.isEmpty()) return 0;

        // Count pre-existing items globally (cross-route dedup: same link = same article)
        String placeholders = String.join(",", Collections.nCopies(valid.size(), "?"));
        int existing = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM feed_items WHERE item_link IN (" + placeholders + ")")) {
            for (int i = 0; i < valid.size(); i++) ps.setString(i + 1, valid.get(i).link());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) existing = rs.getInt(1);
            }
        }

        // Upsert — on conflict keep original route_path and first_seen_at (first route wins)
        String now = Instant.now().toString();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO feed_items
                    (route_path,item_link,title,description,author,pub_date,categories,first_seen_at,last_seen_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(item_link) DO UPDATE SET
                    title        = excluded.title,
                    description  = excluded.description,
                    author       = excluded.author,
                    pub_date     = excluded.pub_date,
                    categories   = excluded.categories,
                    last_seen_at = excluded.last_seen_at
                """)) {
            for (FeedItem item : valid) {
                ps.setString(1, routePath);
                ps.setString(2, item.link());
                ps.setString(3, item.title());
                ps.setString(4, item.description());
                ps.setString(5, item.author());
                ps.setString(6, item.pubDate() != null ? item.pubDate().toString() : null);
                ps.setString(7, toJson(item.categories()));
                ps.setString(8, now);
                ps.setString(9, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        return valid.size() - existing;
    }

    /**
     * Returns the most recently seen items for a route (offline fallback).
     * Items with no link are never stored, so all returned items have a link.
     */
    public List<FeedItem> loadItems(String routePath) throws SQLException {
        List<FeedItem> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT item_link,title,description,author,pub_date,categories
                FROM feed_items
                WHERE route_path=?
                ORDER BY last_seen_at DESC
                LIMIT 50
                """)) {
            ps.setString(1, routePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pubDateStr = rs.getString("pub_date");
                    Instant pubDate = pubDateStr != null ? Instant.parse(pubDateStr) : null;
                    result.add(new FeedItem(
                            rs.getString("title"),
                            rs.getString("item_link"),
                            rs.getString("description"),
                            pubDate,
                            rs.getString("author"),
                            fromJson(rs.getString("categories"))));
                }
            }
        }
        return result;
    }

    /**
     * Builds a Feed from items stored in the DB for offline fallback.
     * Returns an empty Optional when no items have been saved for this path.
     */
    public java.util.Optional<Feed> offlineFeed(String routePath) throws SQLException {
        List<FeedItem> items = loadItems(routePath);
        if (items.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(new Feed(
                "【离线缓存】" + routePath,
                routePath,
                "离线缓存 · 共 " + items.size() + " 条",
                items));
    }

    /**
     * Returns all items first seen within {@code window} of now, newest first.
     * Capped at 500 rows so the reader stays fast regardless of volume.
     */
    public List<StoredItem> loadRecentItems(Duration window) throws SQLException {
        String since = Instant.now().minus(window).toString();
        List<StoredItem> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT route_path, title, item_link, author, pub_date, first_seen_at
                FROM feed_items
                WHERE first_seen_at >= ?
                ORDER BY first_seen_at DESC
                LIMIT 500
                """)) {
            ps.setString(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pubDateStr = rs.getString("pub_date");
                    Instant pubDate = pubDateStr != null ? Instant.parse(pubDateStr) : null;
                    result.add(new StoredItem(
                            rs.getString("route_path"),
                            rs.getString("title"),
                            rs.getString("item_link"),
                            rs.getString("author"),
                            pubDate,
                            Instant.parse(rs.getString("first_seen_at"))));
                }
            }
        }
        return result;
    }

    /**
     * Returns the total number of stored items for a route.
     * Parameterized patterns like {@code /hn/:feed} are matched via SQL LIKE.
     */
    public int countItems(String routePath) throws SQLException {
        String likePattern = routePath.replaceAll("/:[^/]+", "/%");
        boolean exact = likePattern.equals(routePath);
        String sql = exact
                ? "SELECT COUNT(*) FROM feed_items WHERE route_path=?"
                : "SELECT COUNT(*) FROM feed_items WHERE route_path LIKE ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, exact ? routePath : likePattern);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Returns a page of stored items for a route, newest first.
     * Parameterized patterns like {@code /hn/:feed} are matched via SQL LIKE.
     */
    public List<StoredItem> browseItems(String routePath, int offset, int limit) throws SQLException {
        String likePattern = routePath.replaceAll("/:[^/]+", "/%");
        boolean exact = likePattern.equals(routePath);
        String sql = (exact
                ? "SELECT route_path,title,item_link,author,pub_date,first_seen_at FROM feed_items WHERE route_path=?"
                : "SELECT route_path,title,item_link,author,pub_date,first_seen_at FROM feed_items WHERE route_path LIKE ?")
                + " ORDER BY first_seen_at DESC LIMIT ? OFFSET ?";
        List<StoredItem> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, exact ? routePath : likePattern);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pubDateStr = rs.getString("pub_date");
                    Instant pubDate = pubDateStr != null ? Instant.parse(pubDateStr) : null;
                    result.add(new StoredItem(
                            rs.getString("route_path"),
                            rs.getString("title"),
                            rs.getString("item_link"),
                            rs.getString("author"),
                            pubDate,
                            Instant.parse(rs.getString("first_seen_at"))));
                }
            }
        }
        return result;
    }

    // ── fetch log ─────────────────────────────────────────────────────────────

    /**
     * Appends a fetch log entry.
     */
    public synchronized void logFetch(
            String routePath,
            Instant fetchedAt,
            long durationMs,
            int itemCount,
            int newItemCount,
            String errorDetail) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO fetch_log
                    (route_path,fetched_at,duration_ms,item_count,new_item_count,status,error_detail)
                VALUES (?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, routePath);
            ps.setString(2, fetchedAt.toString());
            ps.setLong(3, durationMs);
            ps.setInt(4, itemCount);
            ps.setInt(5, newItemCount);
            ps.setString(6, errorDetail == null ? "ok" : "error");
            ps.setString(7, errorDetail);
            ps.executeUpdate();
        }
    }

    /**
     * Returns the most recent fetch log entries for a route, newest first.
     * Parameterized patterns like {@code /hn/:feed} are matched via SQL LIKE
     * (each {@code :param} segment becomes {@code %}).
     */
    public List<FetchLogEntry> getRecentLogs(String routePath, int limit) throws SQLException {
        String likePattern = routePath.replaceAll("/:[^/]+", "/%");
        boolean exact = likePattern.equals(routePath);
        List<FetchLogEntry> result = new ArrayList<>();
        String sql = exact
                ? "SELECT id,route_path,fetched_at,duration_ms,item_count,new_item_count,status,error_detail FROM fetch_log WHERE route_path=? ORDER BY fetched_at DESC LIMIT ?"
                : "SELECT id,route_path,fetched_at,duration_ms,item_count,new_item_count,status,error_detail FROM fetch_log WHERE route_path LIKE ? ORDER BY fetched_at DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, exact ? routePath : likePattern);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new FetchLogEntry(
                            rs.getLong("id"),
                            rs.getString("route_path"),
                            Instant.parse(rs.getString("fetched_at")),
                            rs.getLong("duration_ms"),
                            rs.getInt("item_count"),
                            rs.getInt("new_item_count"),
                            rs.getString("status"),
                            rs.getString("error_detail")));
                }
            }
        }
        return result;
    }

    /**
     * Returns recent fetch log entries across all routes, newest first.
     * Optionally filtered to routes whose path contains {@code routeFilter} (case-insensitive).
     */
    public List<FetchLogEntry> getAllRecentLogs(String routeFilter, int offset, int limit)
            throws SQLException {
        boolean filtered = routeFilter != null && !routeFilter.isBlank();
        String sql = filtered
                ? """
                  SELECT id,route_path,fetched_at,duration_ms,item_count,new_item_count,status,error_detail
                  FROM fetch_log WHERE route_path LIKE ? ORDER BY fetched_at DESC LIMIT ? OFFSET ?"""
                : """
                  SELECT id,route_path,fetched_at,duration_ms,item_count,new_item_count,status,error_detail
                  FROM fetch_log ORDER BY fetched_at DESC LIMIT ? OFFSET ?""";
        List<FetchLogEntry> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filtered) {
                ps.setString(1, "%" + routeFilter.trim() + "%");
                ps.setInt(2, limit);
                ps.setInt(3, offset);
            } else {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new FetchLogEntry(
                            rs.getLong("id"),
                            rs.getString("route_path"),
                            Instant.parse(rs.getString("fetched_at")),
                            rs.getLong("duration_ms"),
                            rs.getInt("item_count"),
                            rs.getInt("new_item_count"),
                            rs.getString("status"),
                            rs.getString("error_detail")));
                }
            }
        }
        return result;
    }

    /** Counts fetch log entries, optionally filtered by route path substring. */
    public int countLogs(String routeFilter) throws SQLException {
        boolean filtered = routeFilter != null && !routeFilter.isBlank();
        String sql = filtered
                ? "SELECT COUNT(*) FROM fetch_log WHERE route_path LIKE ?"
                : "SELECT COUNT(*) FROM fetch_log";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filtered) ps.setString(1, "%" + routeFilter.trim() + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Counts fetch log entries for today (local timezone). */
    public int countLogsToday() throws SQLException {
        String today = java.time.LocalDate.now().toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM fetch_log WHERE fetched_at >= ?")) {
            ps.setString(1, today);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Counts fetch log error entries for today. */
    public int countErrorsToday() throws SQLException {
        String today = java.time.LocalDate.now().toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM fetch_log WHERE status='error' AND fetched_at >= ?")) {
            ps.setString(1, today);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Returns the most recent successful fetch time for a path, or {@code null} if never fetched.
     */
    public Instant lastFetchedAt(String routePath) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT MAX(fetched_at) FROM fetch_log WHERE route_path=? AND status='ok'")) {
            ps.setString(1, routePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString(1);
                    return val != null ? Instant.parse(val) : null;
                }
                return null;
            }
        }
    }

    // ── ai extracts ───────────────────────────────────────────────────────────

    /**
     * Returns a new list where each item's description is replaced by its AI extract
     * when one exists. Items without a matching extract are returned unchanged.
     * Uses a single batch SELECT for efficiency.
     */
    public List<FeedItem> mergeExtracts(List<FeedItem> items) throws SQLException {
        if (items.isEmpty()) return items;
        List<String> links = items.stream()
                .filter(i -> i.link() != null && !i.link().isBlank())
                .map(FeedItem::link)
                .toList();
        if (links.isEmpty()) return items;
        String placeholders = String.join(",", java.util.Collections.nCopies(links.size(), "?"));
        java.util.Map<String, String> extracts = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
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

    /**
     * Returns the AI-extracted content for an article URL, or {@code null} if not cached.
     */
    public String loadExtract(String itemLink) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT content FROM ai_extracts WHERE item_link=?")) {
            ps.setString(1, itemLink);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("content") : null;
            }
        }
    }

    // ── ai summaries ──────────────────────────────────────────────────────────

    /** Returns the cached summary for an article URL, or {@code null} if not cached. */
    public String loadSummary(String itemLink) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT summary FROM ai_summaries WHERE item_link=?")) {
            ps.setString(1, itemLink);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("summary") : null;
            }
        }
    }

    /**
     * Batch-loads summaries for a list of links.
     * Returns a map of {@code itemLink -> summary} for links that have a cached summary.
     */
    public java.util.Map<String, String> loadSummaries(List<String> links) throws SQLException {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        if (links.isEmpty()) return result;
        String placeholders = String.join(",", java.util.Collections.nCopies(links.size(), "?"));
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT item_link, summary FROM ai_summaries WHERE item_link IN (" + placeholders + ")")) {
            for (int i = 0; i < links.size(); i++) ps.setString(i + 1, links.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getString("item_link"), rs.getString("summary"));
            }
        }
        return result;
    }

    /** Upserts an AI-generated summary for an article URL. */
    public synchronized void saveSummary(String itemLink, String summary, String model) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
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
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    /** Returns the total number of cached AI summaries. */
    public int countSummaries() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM ai_summaries");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Returns the total number of cached AI extracts. */
    public int countExtracts() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM ai_extracts");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Upserts the AI-extracted content for an article URL.
     */
    public synchronized void saveExtract(String itemLink, String content, String model) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
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

    // ── bookmarks ─────────────────────────────────────────────────────────────

    public synchronized void saveBookmark(String link, String title, String routePath)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO bookmarks (item_link, title, route_path, bookmarked_at)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setString(1, link);
            ps.setString(2, title);
            ps.setString(3, routePath);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    public synchronized void removeBookmark(String link) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM bookmarks WHERE item_link = ?")) {
            ps.setString(1, link);
            ps.executeUpdate();
        }
    }

    public boolean isBookmarked(String link) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM bookmarks WHERE item_link = ?")) {
            ps.setString(1, link);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Returns all bookmarked item links for initialising client-side state. */
    public List<String> loadBookmarkLinks() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT item_link FROM bookmarks ORDER BY bookmarked_at DESC")) {
            while (rs.next()) result.add(rs.getString("item_link"));
        }
        return result;
    }

    /** Returns full bookmark records newest first. */
    public List<BookmarkedItem> loadBookmarks() throws SQLException {
        List<BookmarkedItem> result = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT item_link, title, route_path, bookmarked_at
                     FROM bookmarks ORDER BY bookmarked_at DESC
                     """)) {
            while (rs.next()) {
                String ts = rs.getString("bookmarked_at");
                result.add(new BookmarkedItem(
                        rs.getString("item_link"),
                        rs.getString("title"),
                        rs.getString("route_path"),
                        ts != null ? Instant.parse(ts) : null));
            }
        }
        return result;
    }

    // ── read items ────────────────────────────────────────────────────────────

    /** Marks an item as read; does nothing if already marked. */
    public synchronized void markRead(String link) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO read_items (item_link, read_at) VALUES (?, ?)")) {
            ps.setString(1, link);
            ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    /** Removes the read mark for an item. */
    public synchronized void unmarkRead(String link) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM read_items WHERE item_link = ?")) {
            ps.setString(1, link);
            ps.executeUpdate();
        }
    }

    /** Returns true if the item has been marked as read. */
    public boolean isRead(String link) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM read_items WHERE item_link = ?")) {
            ps.setString(1, link);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Returns all read item links, newest first. */
    public List<String> loadReadLinks() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT item_link FROM read_items ORDER BY read_at DESC")) {
            while (rs.next()) result.add(rs.getString("item_link"));
        }
        return result;
    }

    /** Marks all supplied links as read in a single batch. */
    public synchronized void markAllRead(List<String> links) throws SQLException {
        if (links.isEmpty()) return;
        String now = Instant.now().toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO read_items (item_link, read_at) VALUES (?, ?)")) {
            for (String link : links) {
                ps.setString(1, link);
                ps.setString(2, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── search ────────────────────────────────────────────────────────────────

    /**
     * Full-text search across item titles and AI summaries using SQL LIKE.
     * Matches are case-insensitive (SQLite default for ASCII; Chinese characters
     * are matched by substring). Returns at most {@code limit} items, newest first.
     */
    public List<StoredItem> search(String query, int limit) throws SQLException {
        if (query == null || query.isBlank()) return List.of();
        String like = "%" + query + "%";
        List<StoredItem> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT DISTINCT fi.route_path, fi.item_link, fi.title,
                       fi.author, fi.pub_date, fi.first_seen_at
                FROM feed_items fi
                LEFT JOIN ai_summaries s ON s.item_link = fi.item_link
                WHERE fi.title LIKE ? OR s.summary LIKE ?
                ORDER BY fi.first_seen_at DESC
                LIMIT ?
                """)) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pubDateStr = rs.getString("pub_date");
                    Instant pubDate = pubDateStr != null ? Instant.parse(pubDateStr) : null;
                    result.add(new StoredItem(
                            rs.getString("route_path"),
                            rs.getString("title"),
                            rs.getString("item_link"),
                            rs.getString("author"),
                            pubDate,
                            Instant.parse(rs.getString("first_seen_at"))));
                }
            }
        }
        return result;
    }

    // ── health ────────────────────────────────────────────────────────────────

    /**
     * Returns health statistics for every route that has fetch history,
     * computed from the most recent 10 fetch attempts per route.
     * Results are ordered by most-recently-fetched first.
     */
    public Map<String, RouteHealth> loadRouteHealth() throws SQLException {
        Map<String, RouteHealth> result = new LinkedHashMap<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("""
                     WITH latest AS (
                         SELECT route_path, fetched_at, duration_ms, status, error_detail,
                                ROW_NUMBER() OVER (PARTITION BY route_path
                                                   ORDER BY fetched_at DESC) AS rn
                         FROM fetch_log
                     )
                     SELECT
                         route_path,
                         MAX(fetched_at)                                     AS last_fetch_at,
                         MAX(CASE WHEN rn = 1 THEN status       END)         AS last_status,
                         MAX(CASE WHEN rn = 1 THEN error_detail END)         AS last_error,
                         COUNT(*)                                             AS recent_runs,
                         SUM(CASE WHEN status = 'ok' THEN 1 ELSE 0 END)     AS recent_ok,
                         CAST(AVG(duration_ms) AS INTEGER)                   AS avg_duration_ms
                     FROM latest
                     WHERE rn <= 10
                     GROUP BY route_path
                     ORDER BY last_fetch_at DESC
                     """)) {
            while (rs.next()) {
                String path = rs.getString("route_path");
                String lastFetchStr = rs.getString("last_fetch_at");
                Instant lastFetch = lastFetchStr != null ? Instant.parse(lastFetchStr) : null;
                result.put(path, new RouteHealth(
                        path,
                        lastFetch,
                        rs.getString("last_status"),
                        rs.getString("last_error"),
                        rs.getInt("recent_runs"),
                        rs.getInt("recent_ok"),
                        rs.getLong("avg_duration_ms")));
            }
        }
        return result;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }
}
