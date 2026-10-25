package app.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Durable radar background jobs (community retrieval, delivery, evaluation), one queue per kind.
 * A request key is unique per kind, so re-submitting the same request returns the existing job.
 */
public final class RadarJobs {
    private final FeedStore store;

    RadarJobs(FeedStore store) {
        this.store = store;
    }

    static void createSchema(Statement s) throws SQLException {
        s.execute("""
                CREATE TABLE IF NOT EXISTS radar_jobs (
                    id          TEXT PRIMARY KEY,
                    kind        TEXT NOT NULL,
                    request_key TEXT NOT NULL,
                    payload     TEXT NOT NULL,
                    state       TEXT NOT NULL,
                    created_at  INTEGER NOT NULL,
                    UNIQUE (kind, request_key)
                )""");
    }

    public RadarJob createJob(String kind, String requestKey, String payload) throws SQLException {
        synchronized (store) {
            if (requestKey == null || requestKey.isBlank() || requestKey.length() > 200)
                throw new IllegalArgumentException("Invalid request key");
            try (PreparedStatement ps =
                    store.conn.prepareStatement("INSERT OR IGNORE INTO radar_jobs VALUES(?,?,?,?,'queued',?)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, kind);
                ps.setString(3, requestKey);
                ps.setString(4, payload);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    store.conn.prepareStatement("SELECT * FROM radar_jobs WHERE kind=? AND request_key=?")) {
                ps.setString(1, kind);
                ps.setString(2, requestKey);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? job(rs) : null;
                }
            }
        }
    }

    public RadarJob loadJob(String id) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("SELECT * FROM radar_jobs WHERE id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? job(rs) : null;
                }
            }
        }
    }

    public RadarJob claimJob(String kind) throws SQLException {
        synchronized (store) {
            return store.inTransaction(() -> {
                try (PreparedStatement ps = store.conn.prepareStatement(
                        "SELECT id FROM radar_jobs WHERE kind=? AND state='queued' ORDER BY rowid LIMIT 1")) {
                    ps.setString(1, kind);
                    String id;
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return null;
                        id = rs.getString(1);
                    }
                    var job = loadJob(id);
                    updateJob(id, "running", job.payload());
                    return loadJob(id);
                }
            });
        }
    }

    public void updateJob(String id, String state, String payload) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps =
                    store.conn.prepareStatement("UPDATE radar_jobs SET state=?,payload=? WHERE id=?")) {
                ps.setString(1, state);
                ps.setString(2, payload);
                ps.setString(3, id);
                ps.executeUpdate();
            }
        }
    }

    public void recoverJobs(String kind) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "UPDATE radar_jobs SET state='paused' WHERE kind=? AND state='running'")) {
                ps.setString(1, kind);
                ps.executeUpdate();
            }
        }
    }

    private static RadarJob job(ResultSet rs) throws SQLException {
        return new RadarJob(
                rs.getString("id"),
                rs.getString("kind"),
                rs.getString("request_key"),
                rs.getString("state"),
                rs.getString("payload"),
                rs.getLong("created_at"));
    }
}
