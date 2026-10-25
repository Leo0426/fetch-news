package app.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetch history and refresh state: the fetch log, per-route health, HTTP feed snapshots and RSS refresh backoff.
 */
public final class FetchStateStore {
    private final FeedStore store;

    FetchStateStore(FeedStore store) {
        this.store = store;
    }

    public HttpFeedSnapshot loadHttpFeedSnapshot(String url) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    SELECT url, body, etag, last_modified
                    FROM http_feed_snapshots WHERE url = ?
                    """)) {
                ps.setString(1, url);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new HttpFeedSnapshot(
                            rs.getString("url"), rs.getString("body"),
                            rs.getString("etag"), rs.getString("last_modified")) : null;
                }
            }
        }
    }

    public void saveHttpFeedSnapshot(HttpFeedSnapshot snapshot) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    INSERT INTO http_feed_snapshots (url, body, etag, last_modified)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(url) DO UPDATE SET
                        body = excluded.body,
                        etag = excluded.etag,
                        last_modified = excluded.last_modified
                    """)) {
                ps.setString(1, snapshot.url());
                ps.setString(2, snapshot.body());
                ps.setString(3, snapshot.etag());
                ps.setString(4, snapshot.lastModified());
                ps.executeUpdate();
            }
        }
    }

    public void deleteHttpFeedSnapshot(String url) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("DELETE FROM http_feed_snapshots WHERE url = ?")) {
                ps.setString(1, url);
                ps.executeUpdate();
            }
        }
    }

    public FeedRefreshState loadFeedRefreshState(String routePath, String feedUrl) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    SELECT route_path, feed_url, last_checked_at, last_success_at, last_changed_at,
                           next_retry_at, consecutive_failures, last_http_status, response_bytes,
                           last_duration_ms, last_error, content_fingerprint
                    FROM feed_refresh_states WHERE route_path = ? AND feed_url = ?
                    """)) {
                ps.setString(1, routePath);
                ps.setString(2, feedUrl);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? readFeedRefreshState(rs) : null;
                }
            }
        }
    }

    public void saveFeedRefreshState(FeedRefreshState state) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    INSERT INTO feed_refresh_states
                        (route_path, feed_url, last_checked_at, last_success_at, last_changed_at,
                         next_retry_at, consecutive_failures, last_http_status, response_bytes,
                         last_duration_ms, last_error, content_fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(route_path, feed_url) DO UPDATE SET
                        last_checked_at = excluded.last_checked_at,
                        last_success_at = excluded.last_success_at,
                        last_changed_at = excluded.last_changed_at,
                        next_retry_at = excluded.next_retry_at,
                        consecutive_failures = excluded.consecutive_failures,
                        last_http_status = excluded.last_http_status,
                        response_bytes = excluded.response_bytes,
                        last_duration_ms = excluded.last_duration_ms,
                        last_error = excluded.last_error,
                        content_fingerprint = excluded.content_fingerprint
                    """)) {
                ps.setString(1, state.routePath());
                ps.setString(2, state.feedUrl());
                ps.setString(3, formatInstant(state.lastCheckedAt()));
                ps.setString(4, formatInstant(state.lastSuccessAt()));
                ps.setString(5, formatInstant(state.lastChangedAt()));
                ps.setString(6, formatInstant(state.nextRetryAt()));
                ps.setInt(7, state.consecutiveFailures());
                ps.setInt(8, state.lastHttpStatus());
                ps.setLong(9, state.responseBytes());
                ps.setLong(10, state.lastDurationMs());
                ps.setString(11, state.lastError());
                ps.setString(12, state.contentFingerprint());
                ps.executeUpdate();
            }
        }
    }

    public List<FeedRefreshState> loadFeedRefreshStates() throws SQLException {
        synchronized (store) {
            List<FeedRefreshState> states = new ArrayList<>();
            try (Statement statement = store.conn.createStatement();
                 ResultSet rs = statement.executeQuery("""
                         SELECT route_path, feed_url, last_checked_at, last_success_at, last_changed_at,
                                next_retry_at, consecutive_failures, last_http_status, response_bytes,
                                last_duration_ms, last_error, content_fingerprint
                         FROM feed_refresh_states
                         """)) {
                while (rs.next()) {
                    states.add(readFeedRefreshState(rs));
                }
            }
            return states;
        }
    }

    private static FeedRefreshState readFeedRefreshState(ResultSet rs) throws SQLException {
        return new FeedRefreshState(
                rs.getString("route_path"), rs.getString("feed_url"),
                FeedStore.parseInstant(rs.getString("last_checked_at")), FeedStore.parseInstant(rs.getString("last_success_at")),
                FeedStore.parseInstant(rs.getString("last_changed_at")), FeedStore.parseInstant(rs.getString("next_retry_at")),
                rs.getInt("consecutive_failures"), rs.getInt("last_http_status"),
                rs.getLong("response_bytes"), rs.getLong("last_duration_ms"), rs.getString("last_error"),
                rs.getString("content_fingerprint"));
    }

    private static String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    /**
     * Appends a fetch log entry.
     */
    public void logFetch(
            String routePath,
            Instant fetchedAt,
            long durationMs,
            int itemCount,
            int newItemCount,
            String errorDetail) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
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
    }

    public FetchLogEntry latestFetchLog(String routePath) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    SELECT id,route_path,fetched_at,duration_ms,item_count,new_item_count,status,error_detail
                    FROM fetch_log WHERE route_path=? ORDER BY id DESC LIMIT 1
                    """)) {
                ps.setString(1, routePath);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new FetchLogEntry(rs.getLong("id"), rs.getString("route_path"),
                            Instant.parse(rs.getString("fetched_at")), rs.getLong("duration_ms"),
                            rs.getInt("item_count"), rs.getInt("new_item_count"),
                            rs.getString("status"), rs.getString("error_detail"));
                }
            }
        }
    }

    /**
     * Returns the most recent fetch log entries for a route, newest first.
     * Parameterized patterns like {@code /hn/:feed} are matched via SQL LIKE
     * (each {@code :param} segment becomes {@code %}).
     */
    public List<FetchLogEntry> getRecentLogs(String routePath, int limit) throws SQLException {
        synchronized (store) {
            String likePattern = routePath.replaceAll("/:[^/]+", "/%");
            boolean exact = likePattern.equals(routePath);
            List<FetchLogEntry> result = new ArrayList<>();
            String sql = exact
                    ? "SELECT id,route_path,fetched_at,duration_ms,item_count,new_item_count,status,error_detail FROM fetch_log WHERE route_path=? ORDER BY fetched_at DESC LIMIT ?"
                    : "SELECT id,route_path,fetched_at,duration_ms,item_count,new_item_count,status,error_detail FROM fetch_log WHERE route_path LIKE ? ORDER BY fetched_at DESC LIMIT ?";
            try (PreparedStatement ps = store.conn.prepareStatement(sql)) {
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
    }

    /**
     * Returns recent fetch log entries across all routes, newest first.
     * Optionally filtered to routes whose path contains {@code routeFilter} (case-insensitive).
     */
    public List<FetchLogEntry> getAllRecentLogs(String routeFilter, int offset, int limit)
            throws SQLException {
        synchronized (store) {
            boolean filtered = routeFilter != null && !routeFilter.isBlank();
            String sql = filtered
                    ? """
                      SELECT id,route_path,fetched_at,duration_ms,item_count,new_item_count,status,error_detail
                      FROM fetch_log WHERE route_path LIKE ? ORDER BY fetched_at DESC LIMIT ? OFFSET ?"""
                    : """
                      SELECT id,route_path,fetched_at,duration_ms,item_count,new_item_count,status,error_detail
                      FROM fetch_log ORDER BY fetched_at DESC LIMIT ? OFFSET ?""";
            List<FetchLogEntry> result = new ArrayList<>();
            try (PreparedStatement ps = store.conn.prepareStatement(sql)) {
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
    }

    /** Counts fetch log entries, optionally filtered by route path substring. */
    public int countLogs(String routeFilter) throws SQLException {
        synchronized (store) {
            boolean filtered = routeFilter != null && !routeFilter.isBlank();
            String sql = filtered
                    ? "SELECT COUNT(*) FROM fetch_log WHERE route_path LIKE ?"
                    : "SELECT COUNT(*) FROM fetch_log";
            try (PreparedStatement ps = store.conn.prepareStatement(sql)) {
                if (filtered) ps.setString(1, "%" + routeFilter.trim() + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    /** Counts fetch log entries for today (local timezone). */
    public int countLogsToday() throws SQLException {
        synchronized (store) {
            String today = LocalDate.now().toString();
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT COUNT(*) FROM fetch_log WHERE fetched_at >= ?")) {
                ps.setString(1, today);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    /** Counts fetch log error entries for today. */
    public int countErrorsToday() throws SQLException {
        synchronized (store) {
            String today = LocalDate.now().toString();
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT COUNT(*) FROM fetch_log WHERE status='error' AND fetched_at >= ?")) {
                ps.setString(1, today);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    /**
     * Returns the most recent successful fetch time for a path, or {@code null} if never fetched.
     */
    public Instant lastFetchedAt(String routePath) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
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
    }

    /**
     * Returns health statistics for every route that has fetch history,
     * computed from the most recent 10 fetch attempts per route.
     * Results are ordered by most-recently-fetched first.
     */
    public Map<String, RouteHealth> loadRouteHealth() throws SQLException {
        synchronized (store) {
            Map<String, RouteHealth> result = new LinkedHashMap<>();
            try (Statement s = store.conn.createStatement();
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
    }
}
