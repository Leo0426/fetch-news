package app.store;

import app.radar.Profile;
import app.radar.RadarConfig;
import app.radar.RadarTypes;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence for radar reports: immutable run snapshots, analyses, decisions, reports and cached
 * community context. Background jobs live in {@link RadarJobs}.
 */
public final class RadarStore {
    private final FeedStore store;

    RadarStore(FeedStore store) {
        this.store = store;
    }

    static void createSchema(Statement s) throws SQLException {
        s.execute("""
                CREATE TABLE IF NOT EXISTS radar_runs (
                    id          TEXT PRIMARY KEY,
                    request_key TEXT NOT NULL UNIQUE,
                    date        TEXT NOT NULL,
                    zone_id     TEXT NOT NULL,
                    version     INTEGER NOT NULL,
                    state       TEXT NOT NULL,
                    stage       TEXT NOT NULL,
                    calls       INTEGER NOT NULL DEFAULT 0,
                    error       TEXT,
                    snapshot    TEXT NOT NULL,
                    checkpoints TEXT NOT NULL DEFAULT '{}'
                )""");
        s.execute("CREATE INDEX IF NOT EXISTS idx_radar_queue ON radar_runs(state)");
        s.execute("""
                CREATE TABLE IF NOT EXISTS radar_candidates (
                    run_id    TEXT NOT NULL,
                    item_link TEXT NOT NULL,
                    payload   TEXT NOT NULL,
                    PRIMARY KEY (run_id, item_link)
                )""");
        s.execute("CREATE TABLE IF NOT EXISTS radar_analyses (cache_key TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        s.execute("""
                CREATE TABLE IF NOT EXISTS radar_decisions (
                    run_id    TEXT NOT NULL,
                    item_link TEXT NOT NULL,
                    payload   TEXT NOT NULL,
                    PRIMARY KEY (run_id, item_link)
                )""");
        s.execute("CREATE TABLE IF NOT EXISTS community_context (item_link TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        s.execute("""
                CREATE TABLE IF NOT EXISTS radar_reports (
                    run_id   TEXT PRIMARY KEY,
                    payload  TEXT NOT NULL,
                    markdown TEXT NOT NULL
                )""");
    }

    public RadarTypes.RunStatus createRun(
            RadarTypes.RunRequest request, RadarConfig config, Map<String, Profile> profiles) throws SQLException {
        synchronized (store) {
            config.validateProfiles(profiles.keySet());
            return store.inTransaction(() -> {
                try (PreparedStatement ps =
                        store.conn.prepareStatement("SELECT id FROM radar_runs WHERE request_key=?")) {
                    ps.setString(1, request.idempotencyKey());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            var existing = loadRun(rs.getString(1));
                            if (!existing.request().date().equals(request.date())
                                    || !existing.request().zoneId().equals(request.zoneId()))
                                throw new IllegalArgumentException(
                                        "Request key already used for a different date or time zone");
                            return existing.status();
                        }
                    }
                }
                LocalDate date = LocalDate.parse(request.date());
                ZoneId zone = ZoneId.of(request.zoneId());
                Instant from = date.atStartOfDay(zone).toInstant(),
                        to = date.plusDays(1).atStartOfDay(zone).toInstant();
                List<RadarTypes.Candidate> candidates = new ArrayList<>();
                try (PreparedStatement ps = store.conn.prepareStatement("""
                        SELECT * FROM feed_items
                        WHERE substr(first_seen_at, 1, 19) >= ? AND substr(first_seen_at, 1, 19) <= ?""")) {
                    ps.setString(1, from.toString().substring(0, 19));
                    ps.setString(2, to.toString().substring(0, 19));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Instant seen = Instant.parse(rs.getString("first_seen_at"));
                            if (seen.isBefore(from)
                                    || !seen.isBefore(to)
                                    || rs.getString("title") == null
                                    || rs.getString("title").isBlank()) continue;
                            candidates.add(new RadarTypes.Candidate(
                                    rs.getString("item_link"),
                                    rs.getString("title"),
                                    rs.getString("description"),
                                    rs.getString("author"),
                                    rs.getString("pub_date"),
                                    seen.toEpochMilli(),
                                    loadSources(rs.getString("item_link"))));
                        }
                    }
                }
                candidates.sort(Comparator.comparingLong(RadarTypes.Candidate::firstSeenAt)
                        .reversed()
                        .thenComparing(RadarTypes.Candidate::link));
                int total = candidates.size(), version;
                candidates = List.copyOf(candidates.subList(0, Math.min(total, config.candidateLimit())));
                try (PreparedStatement ps = store.conn.prepareStatement(
                        "SELECT COALESCE(MAX(version),0)+1 FROM radar_runs WHERE date=? AND zone_id=?")) {
                    ps.setString(1, request.date());
                    ps.setString(2, request.zoneId());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        version = rs.getInt(1);
                    }
                }
                String id = UUID.randomUUID().toString();
                var status = new RadarTypes.RunStatus(
                        id, request.date(), version, "queued", "routing", total, total - candidates.size(), 0, null);
                var snapshot = new RadarTypes.RunSnapshot(
                        status, request, config, profiles, candidates, System.currentTimeMillis(), Map.of());
                try (PreparedStatement ps = store.conn.prepareStatement("""
                        INSERT INTO radar_runs (id, request_key, date, zone_id, version, state, stage, snapshot)
                        VALUES (?, ?, ?, ?, ?, 'queued', 'routing', ?)""")) {
                    ps.setString(1, id);
                    ps.setString(2, request.idempotencyKey());
                    ps.setString(3, request.date());
                    ps.setString(4, request.zoneId());
                    ps.setInt(5, version);
                    ps.setString(6, toJson(snapshot));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = store.conn.prepareStatement("INSERT INTO radar_candidates VALUES(?,?,?)")) {
                    for (var candidate : candidates) {
                        ps.setString(1, id);
                        ps.setString(2, candidate.link());
                        ps.setString(3, toJson(candidate));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return status;
            });
        }
    }

    private List<String> loadSources(String link) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = store.conn.prepareStatement(
                "SELECT route_path FROM feed_item_sources WHERE item_link=? ORDER BY route_path")) {
            ps.setString(1, link);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }
        }
        return result;
    }

    public app.radar.CommunityContext loadCommunityContext(String link) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps =
                    store.conn.prepareStatement("SELECT payload FROM community_context WHERE item_link=?")) {
                ps.setString(1, link);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    try {
                        return store.mapper.readValue(rs.getString(1), app.radar.CommunityContext.class);
                    } catch (IOException e) {
                        throw new SQLException("Invalid community cache", e);
                    }
                }
            }
        }
    }

    public void saveCommunityContext(app.radar.CommunityContext context) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps =
                    store.conn.prepareStatement("INSERT OR REPLACE INTO community_context VALUES(?,?)")) {
                ps.setString(1, context.link());
                ps.setString(2, store.mapper.writeValueAsString(context));
                ps.executeUpdate();
            } catch (IOException e) {
                throw new SQLException("Cannot save community cache", e);
            }
        }
    }

    public void recordMetrics(String id, app.ai.GenerationMetrics metrics) throws SQLException {
        synchronized (store) {
            var run = loadRun(id);
            long count = Long.parseLong(run.checkpoints().getOrDefault("usage.calls", "0"));
            long known = Long.parseLong(run.checkpoints().getOrDefault("usage.known", "0"));
            long input = Long.parseLong(run.checkpoints().getOrDefault("usage.input", "0"));
            long output = Long.parseLong(run.checkpoints().getOrDefault("usage.output", "0"));
            long millis = Long.parseLong(run.checkpoints().getOrDefault("usage.millis", "0"));
            store.inTransaction(() -> {
                saveCheckpoint(id, "usage.calls", Long.toString(count + 1));
                saveCheckpoint(
                        id,
                        "usage.known",
                        Long.toString(
                                known + (metrics.inputTokens() != null && metrics.outputTokens() != null ? 1 : 0)));
                saveCheckpoint(
                        id,
                        "usage.input",
                        Long.toString(input + (metrics.inputTokens() == null ? 0 : metrics.inputTokens())));
                saveCheckpoint(
                        id,
                        "usage.output",
                        Long.toString(output + (metrics.outputTokens() == null ? 0 : metrics.outputTokens())));
                saveCheckpoint(id, "usage.millis", Long.toString(millis + metrics.elapsedMillis()));
                return null;
            });
        }
    }

    public RadarTypes.RunSnapshot loadRun(String id) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("SELECT * FROM radar_runs WHERE id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    var saved = fromJson(rs.getString("snapshot"), RadarTypes.RunSnapshot.class);
                    var old = saved.status();
                    var status = new RadarTypes.RunStatus(
                            id,
                            old.date(),
                            old.version(),
                            rs.getString("state"),
                            rs.getString("stage"),
                            old.totalCandidates(),
                            old.omitted(),
                            rs.getInt("calls"),
                            rs.getString("error"));
                    Map<String, String> checkpoints;
                    try {
                        checkpoints = store.mapper.readValue(
                                rs.getString("checkpoints"), new TypeReference<Map<String, String>>() {});
                    } catch (IOException e) {
                        throw new SQLException("Invalid radar checkpoint", e);
                    }
                    return new RadarTypes.RunSnapshot(
                            status,
                            saved.request(),
                            saved.config(),
                            saved.profiles(),
                            saved.candidates(),
                            saved.createdAt(),
                            checkpoints);
                }
            }
        }
    }

    public RadarTypes.Analysis loadAnalysis(String cacheKey) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps =
                    store.conn.prepareStatement("SELECT payload FROM radar_analyses WHERE cache_key=?")) {
                ps.setString(1, cacheKey);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? fromJson(rs.getString(1), RadarTypes.Analysis.class) : null;
                }
            }
        }
    }

    public void saveAnalysis(String runId, RadarTypes.Analysis analysis) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps =
                    store.conn.prepareStatement("INSERT OR REPLACE INTO radar_analyses VALUES(?,?)")) {
                ps.setString(1, analysis.cacheKey());
                ps.setString(2, toJson(analysis));
                ps.executeUpdate();
            }
        }
    }

    public void saveDecisions(String runId, List<RadarTypes.Decision> decisions) throws SQLException {
        synchronized (store) {
            store.inTransaction(() -> {
                try (PreparedStatement ps =
                        store.conn.prepareStatement("INSERT OR REPLACE INTO radar_decisions VALUES(?,?,?)")) {
                    for (var decision : decisions) {
                        ps.setString(1, runId);
                        ps.setString(2, decision.link());
                        ps.setString(3, toJson(decision));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            });
        }
    }

    public void saveReport(String runId, RadarTypes.Report report, String markdown) throws SQLException {
        synchronized (store) {
            store.inTransaction(() -> {
                try (PreparedStatement ps = store.conn.prepareStatement("INSERT INTO radar_reports VALUES(?,?,?)")) {
                    ps.setString(1, runId);
                    ps.setString(2, toJson(report));
                    ps.setString(3, markdown);
                    ps.executeUpdate();
                }
                updateState(
                        runId, report.failures() > 0 || report.omitted() > 0 ? "partial" : "success", "render", null);
                return null;
            });
        }
    }

    public void updateState(String id, String state, String stage, String error) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps =
                    store.conn.prepareStatement("UPDATE radar_runs SET state=?,stage=?,error=? WHERE id=?")) {
                ps.setString(1, state);
                ps.setString(2, stage);
                ps.setString(3, error);
                ps.setString(4, id);
                ps.executeUpdate();
            }
        }
    }

    public RadarTypes.RunSnapshot claimNextRun() throws SQLException {
        synchronized (store) {
            return store.inTransaction(() -> {
                String id;
                try (Statement statement = store.conn.createStatement();
                        ResultSet rs = statement.executeQuery(
                                "SELECT id FROM radar_runs WHERE state='queued' ORDER BY rowid LIMIT 1")) {
                    if (!rs.next()) return null;
                    id = rs.getString(1);
                }
                updateState(id, "running", "routing", null);
                return loadRun(id);
            });
        }
    }

    public void recoverRuns() throws SQLException {
        synchronized (store) {
            store.inTransaction(() -> {
                List<String> ids = new ArrayList<>();
                try (Statement statement = store.conn.createStatement();
                        ResultSet rs = statement.executeQuery(
                                "SELECT id FROM radar_runs WHERE state IN ('running','paused')")) {
                    while (rs.next()) ids.add(rs.getString(1));
                }
                for (String id : ids) {
                    var run = loadRun(id);
                    long start = Long.parseLong(run.checkpoints().getOrDefault("attemptStartedAt", "0"));
                    if (start > 0) {
                        long elapsed = Long.parseLong(run.checkpoints().getOrDefault("elapsedMillis", "0"));
                        saveCheckpoint(
                                id,
                                "elapsedMillis",
                                Long.toString(elapsed + Math.max(0, System.currentTimeMillis() - start)));
                        saveCheckpoint(id, "attemptStartedAt", "0");
                    }
                    if (run.status().state().equals("running"))
                        updateState(id, "paused", run.status().stage(), "Application stopped; resume explicitly");
                }
                return null;
            });
        }
    }

    public void saveCheckpoint(String id, String key, String value) throws SQLException {
        synchronized (store) {
            var run = loadRun(id);
            if (run == null) throw new SQLException("Radar run missing");
            Map<String, String> checkpoints = new LinkedHashMap<>(run.checkpoints());
            checkpoints.put(key, value);
            try (PreparedStatement ps = store.conn.prepareStatement("UPDATE radar_runs SET checkpoints=? WHERE id=?")) {
                ps.setString(1, toJson(checkpoints));
                ps.setString(2, id);
                ps.executeUpdate();
            }
        }
    }

    public void incrementCalls(String id) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("UPDATE radar_runs SET calls=calls+1 WHERE id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        }
    }

    public RadarTypes.Report loadReport(String id) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps =
                    store.conn.prepareStatement("SELECT payload FROM radar_reports WHERE run_id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? fromJson(rs.getString(1), RadarTypes.Report.class) : null;
                }
            }
        }
    }

    public String loadMarkdown(String id) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps =
                    store.conn.prepareStatement("SELECT markdown FROM radar_reports WHERE run_id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }
    }

    public RadarTypes.RunSnapshot latestRun(String date, String zone, boolean publishedOnly) throws SQLException {
        synchronized (store) {
            String condition = publishedOnly
                    ? " AND state IN ('success','partial') ORDER BY CASE state WHEN 'success' THEN 0 ELSE 1 END, version DESC"
                    : " ORDER BY version DESC";
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT id FROM radar_runs WHERE date=? AND zone_id=?" + condition + " LIMIT 1")) {
                ps.setString(1, date);
                ps.setString(2, zone);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? loadRun(rs.getString(1)) : null;
                }
            }
        }
    }

    private String toJson(Object value) throws SQLException {
        try {
            return store.mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new SQLException("Cannot serialize radar data", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) throws SQLException {
        try {
            return store.mapper.readValue(json, type);
        } catch (IOException e) {
            throw new SQLException("Cannot load radar data", e);
        }
    }
}
