package app.store;

import app.core.FeedItem;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The durable AI summary queue in {@code ai_summary_runs}. Interactive requests are claimed before
 * background work; background tasks expire after {@link #BACKGROUND_TTL} or once someone reads the article.
 */
public final class SummaryQueue {
    /** Who asked for a summary. Interactive requests always run before background ones. */
    public enum Priority {
        INTERACTIVE(0), BACKGROUND(1);

        final int rank;

        Priority(int rank) {
            this.rank = rank;
        }

        static Priority of(int rank) {
            return rank == INTERACTIVE.rank ? INTERACTIVE : BACKGROUND;
        }
    }

    /** A claimed task: the article to summarise and the priority it was queued with. */
    public record Claim(FeedItem item, Priority priority) {}

    static final Duration BACKGROUND_TTL = Duration.ofHours(24);
    // SQLite compares queued timestamps as text; fixed precision preserves nanosecond order.
    private static final DateTimeFormatter QUEUE_TIME_FORMAT =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    private final FeedStore store;

    SummaryQueue(FeedStore store) {
        this.store = store;
    }

    static void createSchema(Statement s) throws SQLException {
        s.execute("""
                CREATE TABLE IF NOT EXISTS ai_summary_runs (
                    item_link    TEXT PRIMARY KEY,
                    status       TEXT NOT NULL,
                    started_at   TEXT NOT NULL,
                    completed_at TEXT,
                    duration_ms  INTEGER,
                    model        TEXT,
                    error_detail TEXT,
                    priority     INTEGER NOT NULL DEFAULT 1,
                    attempts     INTEGER NOT NULL DEFAULT 0
                )""");
        if (FeedSchema.addColumnIfMissing(s, "ai_summary_runs", "priority", "INTEGER NOT NULL DEFAULT 1")) {
            // Before priorities existed every fetched item was queued; those backlogs are dropped once.
            s.execute("DELETE FROM ai_summary_runs WHERE status <> 'success'");
    }
            FeedSchema.addColumnIfMissing(s, "ai_summary_runs", "attempts", "INTEGER NOT NULL DEFAULT 0");
            s.execute("DROP INDEX IF EXISTS idx_ai_summary_queue");
            s.execute("""
                    CREATE INDEX IF NOT EXISTS idx_ai_summary_priority_queue
                        ON ai_summary_runs(status, priority, started_at)""");
    }

    /** Queues a background summary; see {@link #enqueue(String, Instant, boolean, Priority)}. */
    public boolean enqueue(String link, Instant queuedAt, boolean retryFailed) throws SQLException {
        return enqueue(link, queuedAt, retryFailed, Priority.BACKGROUND);
    }

    /**
     * Queues a summary unless one exists or is already pending. A pending background task asked for
     * interactively is promoted instead. Failed tasks are requeued only when {@code retryFailed}.
     */
    public boolean enqueue(String link, Instant queuedAt, boolean retryFailed, Priority priority)
            throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    UPDATE ai_summary_runs SET priority = ?
                    WHERE item_link = ? AND status = 'queued' AND priority > ?
                    """)) {
                ps.setInt(1, priority.rank);
                ps.setString(2, link);
                ps.setInt(3, priority.rank);
                if (ps.executeUpdate() > 0) return true;
            }
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    INSERT INTO ai_summary_runs
                        (item_link, status, started_at, completed_at, duration_ms, model, error_detail, priority, attempts)
                    SELECT item_link, 'queued', ?, NULL, NULL, '', NULL, ?, 0
                    FROM feed_items fi WHERE item_link = ?
                        AND NOT EXISTS (SELECT 1 FROM ai_summaries s
                            WHERE s.item_link = fi.item_link
                              AND trim(s.summary, char(9, 10, 11, 12, 13, 32)) <> '')
                    ON CONFLICT(item_link) DO UPDATE SET
                        status = 'queued', started_at = excluded.started_at,
                        completed_at = NULL, duration_ms = NULL, model = '', error_detail = NULL,
                        priority = excluded.priority, attempts = 0
                    WHERE ai_summary_runs.status = 'error' AND ?
                    """)) {
                ps.setString(1, QUEUE_TIME_FORMAT.format(queuedAt));
                ps.setInt(2, priority.rank);
                ps.setString(3, link);
                ps.setBoolean(4, retryFailed);
                return ps.executeUpdate() > 0;
            }
        }
    }

    /**
     * Claims the next queued summary, interactive requests first. Background tasks that waited longer
     * than {@link #BACKGROUND_TTL} or whose article someone already read are dropped first.
     */
    public Claim claimNext(Instant startedAt, String model) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    DELETE FROM ai_summary_runs
                    WHERE status = 'queued' AND priority = ?
                        AND (started_at < ?
                             OR EXISTS (SELECT 1 FROM read_items r WHERE r.item_link = ai_summary_runs.item_link))
                    """)) {
                ps.setInt(1, Priority.BACKGROUND.rank);
                ps.setString(2, QUEUE_TIME_FORMAT.format(startedAt.minus(BACKGROUND_TTL)));
                ps.executeUpdate();
            }
            String link;
            int rank;
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    UPDATE ai_summary_runs
                    SET status = 'running', started_at = ?, model = ?
                    WHERE item_link = (
                        SELECT r.item_link FROM ai_summary_runs r
                        JOIN feed_items fi ON fi.item_link = r.item_link
                        WHERE r.status = 'queued'
                            AND NOT EXISTS (SELECT 1 FROM ai_summaries s
                                WHERE s.item_link = r.item_link
                                  AND trim(s.summary, char(9, 10, 11, 12, 13, 32)) <> '')
                        ORDER BY r.priority, r.started_at, r.rowid LIMIT 1)
                    RETURNING item_link, priority
                    """)) {
                ps.setString(1, startedAt.toString());
                ps.setString(2, model);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    link = rs.getString("item_link");
                    rank = rs.getInt("priority");
                }
            }
            return new Claim(store.loadItem(link), Priority.of(rank));
        }
    }

    /** Puts a running task back in the queue once, keeping its priority. Returns false if it was already retried. */
    public boolean retryOnce(String link, Instant queuedAt) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    UPDATE ai_summary_runs
                    SET status = 'queued', started_at = ?, attempts = attempts + 1
                    WHERE item_link = ? AND status = 'running' AND attempts = 0
                    """)) {
                ps.setString(1, QUEUE_TIME_FORMAT.format(queuedAt));
                ps.setString(2, link);
                return ps.executeUpdate() > 0;
            }
        }
    }

    /**
     * Resolves tasks left running by a restart: interactive ones go back to the queue,
     * background ones fail so they are not silently resumed.
     */
    public void recoverInterrupted(Instant now) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    UPDATE ai_summary_runs SET status = 'queued', started_at = ?
                    WHERE status = 'running' AND priority = ?
                        AND NOT EXISTS (SELECT 1 FROM ai_summaries s WHERE s.item_link = ai_summary_runs.item_link
                            AND trim(s.summary, char(9, 10, 11, 12, 13, 32)) <> '')
                    """)) {
                ps.setString(1, QUEUE_TIME_FORMAT.format(now));
                ps.setInt(2, Priority.INTERACTIVE.rank);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    UPDATE ai_summary_runs
                    SET status = CASE WHEN EXISTS (
                            SELECT 1 FROM ai_summaries s WHERE s.item_link = ai_summary_runs.item_link
                                AND trim(s.summary, char(9, 10, 11, 12, 13, 32)) <> '')
                            THEN 'success' ELSE 'error' END,
                        completed_at = ?, duration_ms = NULL,
                        error_detail = CASE WHEN EXISTS (
                            SELECT 1 FROM ai_summaries s WHERE s.item_link = ai_summary_runs.item_link
                                AND trim(s.summary, char(9, 10, 11, 12, 13, 32)) <> '')
                            THEN NULL ELSE 'Task interrupted by application restart; retry to continue.' END
                    WHERE status = 'running'
                    """)) {
                ps.setString(1, now.toString());
                ps.executeUpdate();
            }
        }
    }

    /** Records that AI summarization has finished for an article URL. */
    public void markFinished(
            String itemLink,
            Instant startedAt,
            Instant completedAt,
            String model,
            String errorDetail) throws SQLException {
        synchronized (store) {
            long durationMs = Duration.between(startedAt, completedAt).toMillis();
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    INSERT INTO ai_summary_runs
                        (item_link, status, started_at, completed_at, duration_ms, model, error_detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(item_link) DO UPDATE SET
                        status       = excluded.status,
                        started_at   = excluded.started_at,
                        completed_at = excluded.completed_at,
                        duration_ms  = excluded.duration_ms,
                        model        = excluded.model,
                        error_detail = excluded.error_detail
                    """)) {
                ps.setString(1, itemLink);
                ps.setString(2, errorDetail == null ? "success" : "error");
                ps.setString(3, startedAt.toString());
                ps.setString(4, completedAt.toString());
                ps.setLong(5, durationMs);
                ps.setString(6, model);
                ps.setString(7, errorDetail);
                ps.executeUpdate();
            }
        }
    }

    /** Batch-loads latest AI summarization status for a list of links. */
    public Map<String, SummaryStatus> statuses(List<String> links) throws SQLException {
        synchronized (store) {
            Map<String, SummaryStatus> result = new LinkedHashMap<>();
            if (links.isEmpty()) return result;
            String placeholders = String.join(",", Collections.nCopies(links.size(), "?"));
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    SELECT item_link, status, started_at, completed_at, duration_ms, model, error_detail
                    FROM ai_summary_runs
                    WHERE item_link IN (%s)
                    """.formatted(placeholders))) {
                for (int i = 0; i < links.size(); i++) ps.setString(i + 1, links.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String started = rs.getString("started_at");
                        String completed = rs.getString("completed_at");
                        long duration = rs.getLong("duration_ms");
                        Long durationMs = rs.wasNull() ? null : duration;
                        result.put(rs.getString("item_link"), new SummaryStatus(
                                rs.getString("item_link"),
                                rs.getString("status"),
                                started != null ? Instant.parse(started) : null,
                                completed != null ? Instant.parse(completed) : null,
                                durationMs,
                                rs.getString("model"),
                                rs.getString("error_detail")));
                    }
                }
            }
            List<String> missing = links.stream()
                    .filter(link -> link != null && !link.isBlank())
                    .filter(link -> !result.containsKey(link))
                    .distinct()
                    .toList();
            if (!missing.isEmpty()) {
                String missingPlaceholders = String.join(",", Collections.nCopies(missing.size(), "?"));
                try (PreparedStatement ps = store.conn.prepareStatement("""
                        SELECT item_link, model, created_at
                        FROM ai_summaries
                        WHERE item_link IN (%s)
                        """.formatted(missingPlaceholders))) {
                    for (int i = 0; i < missing.size(); i++) ps.setString(i + 1, missing.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String created = rs.getString("created_at");
                            Instant createdAt = created != null ? Instant.parse(created) : null;
                            result.put(rs.getString("item_link"), new SummaryStatus(
                                    rs.getString("item_link"),
                                    "success",
                                    createdAt,
                                    createdAt,
                                    0L,
                                    rs.getString("model"),
                                    null));
                        }
                    }
                }
            }
            return result;
        }
    }
}
