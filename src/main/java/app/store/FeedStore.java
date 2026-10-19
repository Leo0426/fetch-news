package app.store;

import app.core.Feed;
import app.core.FeedItem;
import app.radar.Profile;
import app.radar.RadarConfig;
import app.radar.RadarTypes;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite-backed store for feed items and fetch logs.
 *
 * <p>All database access is synchronized because statements on the shared connection
 * otherwise see in-progress transactions. WAL permits readers on separate connections.
 */
public class FeedStore implements Closeable {

    private static final int SEARCH_INDEX_VERSION = 1;
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};
    private static final DateTimeFormatter QUEUE_TIME_FORMAT =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private static final String UNREAD_CONDITION = """
            trim(fi.title, char(9, 10, 11, 12, 13, 32)) <> ''
            AND trim(fi.item_link, char(9, 10, 11, 12, 13, 32)) <> ''
            AND NOT EXISTS (SELECT 1 FROM read_items r WHERE r.item_link = fi.item_link)
            """;

    private final Connection conn;
    private final ObjectMapper mapper;

    public FeedStore(Path dbPath, ObjectMapper mapper) throws SQLException {
        this.mapper = mapper;
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try {
            init();
        } catch (SQLException | RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    private void initializeRadarSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS feed_item_sources(item_link TEXT NOT NULL, route_path TEXT NOT NULL, first_seen_at TEXT NOT NULL, last_seen_at TEXT NOT NULL, PRIMARY KEY(item_link,route_path))");
            s.execute("INSERT OR IGNORE INTO feed_item_sources SELECT item_link,route_path,first_seen_at,last_seen_at FROM feed_items");
            s.execute("CREATE TABLE IF NOT EXISTS radar_runs(id TEXT PRIMARY KEY, request_key TEXT NOT NULL UNIQUE, date TEXT NOT NULL, zone_id TEXT NOT NULL, version INTEGER NOT NULL, state TEXT NOT NULL, stage TEXT NOT NULL, calls INTEGER NOT NULL DEFAULT 0, error TEXT, snapshot TEXT NOT NULL, checkpoints TEXT NOT NULL DEFAULT '{}')");
            s.execute("CREATE INDEX IF NOT EXISTS idx_radar_queue ON radar_runs(state)");
            s.execute("CREATE TABLE IF NOT EXISTS radar_candidates(run_id TEXT NOT NULL,item_link TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(run_id,item_link))");
            s.execute("CREATE TABLE IF NOT EXISTS radar_analyses(cache_key TEXT PRIMARY KEY,payload TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS radar_decisions(run_id TEXT NOT NULL,item_link TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(run_id,item_link))");
            s.execute("CREATE TABLE IF NOT EXISTS radar_reports(run_id TEXT PRIMARY KEY,payload TEXT NOT NULL,markdown TEXT NOT NULL)");
        }
    }

    public synchronized RadarTypes.RunStatus createRadarRun(RadarTypes.RunRequest request, RadarConfig config,
                                                             Map<String,Profile> profiles) throws SQLException {
        config.validateProfiles(profiles.keySet());
        return inTransaction(() -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM radar_runs WHERE request_key=?")) {
                ps.setString(1, request.idempotencyKey());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        var existing=loadRadarRun(rs.getString(1));
                        if(!existing.request().date().equals(request.date()) || !existing.request().zoneId().equals(request.zoneId()))
                            throw new IllegalArgumentException("Request key already used for a different date or time zone");
                        return existing.status();
                    }
                }
            }
            LocalDate date = LocalDate.parse(request.date());
            ZoneId zone = ZoneId.of(request.zoneId());
            Instant from = date.atStartOfDay(zone).toInstant(), to = date.plusDays(1).atStartOfDay(zone).toInstant();
            List<RadarTypes.Candidate> candidates = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM feed_items WHERE substr(first_seen_at,1,19)>=? AND substr(first_seen_at,1,19)<=?")) {
                ps.setString(1, from.toString().substring(0,19)); ps.setString(2, to.toString().substring(0,19));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Instant seen = Instant.parse(rs.getString("first_seen_at"));
                        if (seen.isBefore(from) || !seen.isBefore(to) || rs.getString("title") == null || rs.getString("title").isBlank()) continue;
                        candidates.add(new RadarTypes.Candidate(rs.getString("item_link"), rs.getString("title"), rs.getString("description"),
                                rs.getString("author"), rs.getString("pub_date"), seen.toEpochMilli(), loadRadarSources(rs.getString("item_link"))));
                    }
                }
            }
            candidates.sort(Comparator.comparingLong(RadarTypes.Candidate::firstSeenAt).reversed().thenComparing(RadarTypes.Candidate::link));
            int total = candidates.size(), version;
            candidates = List.copyOf(candidates.subList(0, Math.min(total, config.candidateLimit())));
            try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(version),0)+1 FROM radar_runs WHERE date=? AND zone_id=?")) {
                ps.setString(1, request.date()); ps.setString(2, request.zoneId());
                try (ResultSet rs = ps.executeQuery()) { rs.next(); version = rs.getInt(1); }
            }
            String id = java.util.UUID.randomUUID().toString();
            var status = new RadarTypes.RunStatus(id, request.date(), version, "queued", "routing", total, total-candidates.size(), 0, null);
            var snapshot = new RadarTypes.RunSnapshot(status, request, config, profiles, candidates, System.currentTimeMillis(), Map.of());
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO radar_runs(id,request_key,date,zone_id,version,state,stage,snapshot) VALUES(?,?,?,?,?,'queued','routing',?)")) {
                ps.setString(1,id); ps.setString(2,request.idempotencyKey()); ps.setString(3,request.date());
                ps.setString(4,request.zoneId()); ps.setInt(5,version); ps.setString(6,radarJson(snapshot)); ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO radar_candidates VALUES(?,?,?)")) {
                for (var candidate : candidates) {
                    ps.setString(1,id); ps.setString(2,candidate.link()); ps.setString(3,radarJson(candidate)); ps.addBatch();
                }
                ps.executeBatch();
            }
            return status;
        });
    }

    private List<String> loadRadarSources(String link) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT route_path FROM feed_item_sources WHERE item_link=? ORDER BY route_path")) {
            ps.setString(1,link);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(rs.getString(1)); }
        }
        return result;
    }

    public synchronized List<RadarTypes.RunSnapshot> listRadarRuns(int limit) throws SQLException {
        List<RadarTypes.RunSnapshot> result = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement("SELECT id FROM radar_runs ORDER BY rowid DESC LIMIT ?")) {
            statement.setInt(1, Math.clamp(limit, 1, 100));
            try (ResultSet rs = statement.executeQuery()) { while (rs.next()) result.add(loadRadarRun(rs.getString(1))); }
        }
        return List.copyOf(result);
    }

    public synchronized void recordRadarMetrics(String id, app.ai.GenerationMetrics metrics) throws SQLException {
        var run = loadRadarRun(id);
        long count = Long.parseLong(run.checkpoints().getOrDefault("usage.calls", "0"));
        long known = Long.parseLong(run.checkpoints().getOrDefault("usage.known", "0"));
        long input = Long.parseLong(run.checkpoints().getOrDefault("usage.input", "0"));
        long output = Long.parseLong(run.checkpoints().getOrDefault("usage.output", "0"));
        long millis = Long.parseLong(run.checkpoints().getOrDefault("usage.millis", "0"));
        inTransaction(() -> {
            saveRadarCheckpoint(id, "usage.calls", Long.toString(count + 1));
            saveRadarCheckpoint(id, "usage.known", Long.toString(known + (metrics.inputTokens() != null && metrics.outputTokens() != null ? 1 : 0)));
            saveRadarCheckpoint(id, "usage.input", Long.toString(input + (metrics.inputTokens() == null ? 0 : metrics.inputTokens())));
            saveRadarCheckpoint(id, "usage.output", Long.toString(output + (metrics.outputTokens() == null ? 0 : metrics.outputTokens())));
            saveRadarCheckpoint(id, "usage.millis", Long.toString(millis + metrics.elapsedMillis()));
            return null;
        });
    }

    public synchronized RadarTypes.RunSnapshot loadRadarRun(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM radar_runs WHERE id=?")) {
            ps.setString(1,id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                var saved = radarRead(rs.getString("snapshot"), RadarTypes.RunSnapshot.class);
                var old = saved.status();
                var status = new RadarTypes.RunStatus(id, old.date(), old.version(), rs.getString("state"), rs.getString("stage"),
                        old.totalCandidates(), old.omitted(), rs.getInt("calls"), rs.getString("error"));
                Map<String,String> checkpoints;
                try { checkpoints = mapper.readValue(rs.getString("checkpoints"), new TypeReference<Map<String,String>>() {}); }
                catch (IOException e) { throw new SQLException("Invalid radar checkpoint",e); }
                return new RadarTypes.RunSnapshot(status, saved.request(), saved.config(), saved.profiles(), saved.candidates(), saved.createdAt(), checkpoints);
            }
        }
    }

    public synchronized RadarTypes.Analysis loadRadarAnalysis(String cacheKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT payload FROM radar_analyses WHERE cache_key=?")) {
            ps.setString(1,cacheKey);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? radarRead(rs.getString(1),RadarTypes.Analysis.class) : null; }
        }
    }

    public synchronized void saveRadarAnalysis(String runId, RadarTypes.Analysis analysis) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO radar_analyses VALUES(?,?)")) {
            ps.setString(1,analysis.cacheKey()); ps.setString(2,radarJson(analysis)); ps.executeUpdate();
        }
    }

    public synchronized void saveRadarDecisions(String runId, List<RadarTypes.Decision> decisions) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO radar_decisions VALUES(?,?,?)")) {
                for (var decision : decisions) { ps.setString(1,runId); ps.setString(2,decision.link()); ps.setString(3,radarJson(decision)); ps.addBatch(); }
                ps.executeBatch();
            }
            return null;
        });
    }

    public synchronized void saveRadarReport(String runId, RadarTypes.Report report, String markdown) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO radar_reports VALUES(?,?,?)")) {
                ps.setString(1,runId); ps.setString(2,radarJson(report)); ps.setString(3,markdown); ps.executeUpdate();
            }
            updateRadarState(runId, report.failures() > 0 || report.omitted() > 0 ? "partial" : "success", "render", null);
            return null;
        });
    }

    public synchronized void updateRadarState(String id, String state, String stage, String error) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE radar_runs SET state=?,stage=?,error=? WHERE id=?")) {
            ps.setString(1,state); ps.setString(2,stage); ps.setString(3,error); ps.setString(4,id); ps.executeUpdate();
        }
    }

    public synchronized RadarTypes.RunSnapshot claimNextRadarRun() throws SQLException {
        return inTransaction(() -> {
            String id;
            try (Statement statement=conn.createStatement(); ResultSet rs=statement.executeQuery("SELECT id FROM radar_runs WHERE state='queued' ORDER BY rowid LIMIT 1")) {
                if (!rs.next()) return null;
                id=rs.getString(1);
            }
            updateRadarState(id,"running","routing",null);
            return loadRadarRun(id);
        });
    }

    public synchronized void recoverRadarRuns() throws SQLException {
        inTransaction(() -> {
            List<String> ids=new ArrayList<>();
            try (Statement statement=conn.createStatement(); ResultSet rs=statement.executeQuery("SELECT id FROM radar_runs WHERE state IN ('running','paused')")) {
                while(rs.next()) ids.add(rs.getString(1));
            }
            for(String id:ids) {
                var run=loadRadarRun(id);
                long start=Long.parseLong(run.checkpoints().getOrDefault("attemptStartedAt","0"));
                if(start>0) {
                    long elapsed=Long.parseLong(run.checkpoints().getOrDefault("elapsedMillis","0"));
                    saveRadarCheckpoint(id,"elapsedMillis",Long.toString(elapsed+Math.max(0,System.currentTimeMillis()-start)));
                    saveRadarCheckpoint(id,"attemptStartedAt","0");
                }
                if(run.status().state().equals("running")) updateRadarState(id,"paused",run.status().stage(),"Application stopped; resume explicitly");
            }
            return null;
        });
    }

    public synchronized void saveRadarCheckpoint(String id,String key,String value) throws SQLException {
        var run=loadRadarRun(id);
        if (run==null) throw new SQLException("Radar run missing");
        Map<String,String> checkpoints=new LinkedHashMap<>(run.checkpoints()); checkpoints.put(key,value);
        try (PreparedStatement ps=conn.prepareStatement("UPDATE radar_runs SET checkpoints=? WHERE id=?")) {
            ps.setString(1,radarJson(checkpoints)); ps.setString(2,id); ps.executeUpdate();
        }
    }

    public synchronized void incrementRadarCalls(String id) throws SQLException {
        try (PreparedStatement ps=conn.prepareStatement("UPDATE radar_runs SET calls=calls+1 WHERE id=?")) {
            ps.setString(1,id); ps.executeUpdate();
        }
    }

    public synchronized RadarTypes.Report loadRadarReport(String id) throws SQLException {
        try (PreparedStatement ps=conn.prepareStatement("SELECT payload FROM radar_reports WHERE run_id=?")) {
            ps.setString(1,id);
            try (ResultSet rs=ps.executeQuery()) { return rs.next() ? radarRead(rs.getString(1),RadarTypes.Report.class) : null; }
        }
    }

    public synchronized String loadRadarMarkdown(String id) throws SQLException {
        try (PreparedStatement ps=conn.prepareStatement("SELECT markdown FROM radar_reports WHERE run_id=?")) {
            ps.setString(1,id);
            try (ResultSet rs=ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    public synchronized RadarTypes.RunSnapshot latestRadarRun(String date,String zone,boolean publishedOnly) throws SQLException {
        String condition=publishedOnly ? " AND state IN ('success','partial') ORDER BY CASE state WHEN 'success' THEN 0 ELSE 1 END,version DESC" : " ORDER BY version DESC";
        try (PreparedStatement ps=conn.prepareStatement("SELECT id FROM radar_runs WHERE date=? AND zone_id=?"+condition+" LIMIT 1")) {
            ps.setString(1,date); ps.setString(2,zone);
            try (ResultSet rs=ps.executeQuery()) { return rs.next() ? loadRadarRun(rs.getString(1)) : null; }
        }
    }

    private String radarJson(Object value) throws SQLException {
        try { return mapper.writeValueAsString(value); }
        catch (IOException e) { throw new SQLException("Cannot serialize radar data", e); }
    }

    private <T> T radarRead(String json, Class<T> type) throws SQLException {
        try { return mapper.readValue(json,type); }
        catch (IOException e) { throw new SQLException("Cannot load radar data", e); }
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
        inTransaction(() -> {
            initializeSchema();
            initializeRadarSchema();
            return null;
        });
    }

    private void initializeSchema() throws SQLException {
        // Migration check must run before CREATE TABLE IF NOT EXISTS so that the
        // old schema (UNIQUE on route_path+item_link) is replaced before any new
        // rows are written under the new global-dedup constraint.
        boolean migratedItems = hasOldFeedItemsSchema();
        if (migratedItems) {
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
                    CREATE TABLE IF NOT EXISTS http_feed_snapshots (
                        url           TEXT PRIMARY KEY NOT NULL,
                        body          TEXT NOT NULL,
                        etag          TEXT,
                        last_modified TEXT
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS feed_refresh_states (
                        route_path           TEXT NOT NULL,
                        feed_url             TEXT NOT NULL,
                        last_checked_at      TEXT,
                        last_success_at      TEXT,
                        last_changed_at      TEXT,
                        next_retry_at        TEXT,
                        consecutive_failures INTEGER NOT NULL,
                        last_http_status     INTEGER NOT NULL,
                        response_bytes       INTEGER NOT NULL,
                        last_duration_ms     INTEGER NOT NULL,
                        last_error           TEXT,
                        content_fingerprint  TEXT,
                        PRIMARY KEY (route_path, feed_url)
                    )""");
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
                    CREATE TABLE IF NOT EXISTS ai_summary_runs (
                        item_link    TEXT PRIMARY KEY,
                        status       TEXT NOT NULL,
                        started_at   TEXT NOT NULL,
                        completed_at TEXT,
                        duration_ms  INTEGER,
                        model        TEXT,
                        error_detail TEXT
                    )""");
            s.execute("""
                    CREATE INDEX IF NOT EXISTS idx_ai_summary_queue
                        ON ai_summary_runs(status, started_at)""");
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
        initializeSearchIndex(migratedItems);
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
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws SQLException;
    }

    private <T> T inTransaction(SqlOperation<T> operation) throws SQLException {
        conn.setAutoCommit(false);
        T result;
        try {
            result = operation.run();
            conn.commit();
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                conn.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                closeAfterFailure(failure);
                throw failure;
            }
            try {
                conn.setAutoCommit(true);
            } catch (SQLException resetFailure) {
                failure.addSuppressed(resetFailure);
                closeAfterFailure(failure);
            }
            throw failure;
        }
        try {
            conn.setAutoCommit(true);
        } catch (SQLException failure) {
            closeAfterFailure(failure);
            throw failure;
        }
        return result;
    }

    private void closeAfterFailure(Throwable failure) {
        try {
            conn.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    public synchronized HttpFeedSnapshot loadHttpFeedSnapshot(String url) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
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

    public synchronized void saveHttpFeedSnapshot(HttpFeedSnapshot snapshot) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
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

    public synchronized void deleteHttpFeedSnapshot(String url) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM http_feed_snapshots WHERE url = ?")) {
            ps.setString(1, url);
            ps.executeUpdate();
        }
    }

    public synchronized FeedRefreshState loadFeedRefreshState(String routePath, String feedUrl) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
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

    public synchronized void saveFeedRefreshState(FeedRefreshState state) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
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

    public synchronized List<FeedRefreshState> loadFeedRefreshStates() throws SQLException {
        List<FeedRefreshState> states = new ArrayList<>();
        try (Statement statement = conn.createStatement();
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

    private static FeedRefreshState readFeedRefreshState(ResultSet rs) throws SQLException {
        return new FeedRefreshState(
                rs.getString("route_path"), rs.getString("feed_url"),
                parseInstant(rs.getString("last_checked_at")), parseInstant(rs.getString("last_success_at")),
                parseInstant(rs.getString("last_changed_at")), parseInstant(rs.getString("next_retry_at")),
                rs.getInt("consecutive_failures"), rs.getInt("last_http_status"),
                rs.getLong("response_bytes"), rs.getLong("last_duration_ms"), rs.getString("last_error"),
                rs.getString("content_fingerprint"));
    }

    private static String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private static Instant parseInstant(String value) {
        return value != null ? Instant.parse(value) : null;
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

        return inTransaction(() -> upsertItems(routePath, valid));
    }

    private int upsertItems(String routePath, List<FeedItem> valid) throws SQLException {
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
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO feed_item_sources(item_link,route_path,first_seen_at,last_seen_at) VALUES(?,?,?,?)
                ON CONFLICT(item_link,route_path) DO UPDATE SET last_seen_at=excluded.last_seen_at
                """)) {
            for (FeedItem item : valid) {
                ps.setString(1, item.link()); ps.setString(2, routePath);
                ps.setString(3, now); ps.setString(4, now); ps.addBatch();
            }
            ps.executeBatch();
        }
        refreshSearchIndex(valid.stream().map(FeedItem::link).toList());

        return valid.size() - existing;
    }

    /**
     * Returns the most recently seen items for a route (offline fallback).
     * Items with no link are never stored, so all returned items have a link.
     */
    public synchronized List<FeedItem> loadItems(String routePath) throws SQLException {
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
    public synchronized java.util.Optional<Feed> offlineFeed(String routePath) throws SQLException {
        List<FeedItem> items = loadItems(routePath);
        if (items.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(new Feed(
                "【离线缓存】" + routePath,
                routePath,
                "离线缓存 · 共 " + items.size() + " 条",
                items));
    }

    /**
     * Returns items for {@code routePath} whose {@code first_seen_at} is at or after {@code since},
     * newest first. Used to identify items discovered in a single scheduled fetch.
     */
    public synchronized List<StoredItem> loadNewItemsSince(String routePath, Instant since) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT route_path, title, item_link, author, pub_date, first_seen_at
                FROM feed_items
                WHERE route_path = ? AND first_seen_at >= ?
                """)) {
            ps.setString(1, routePath);
            ps.setString(2, firstSeenLowerBound(since));
            try (ResultSet rs = ps.executeQuery()) {
                return readNewItemsSince(rs, since);
            }
        }
    }

    /**
     * Returns items across multiple routes whose {@code first_seen_at} is at or after
     * {@code since}, newest first. Used for category-level digest messages.
     */
    public synchronized List<StoredItem> loadNewItemsSinceForRoutes(List<String> routePaths, Instant since)
            throws SQLException {
        if (routePaths.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(routePaths.size(), "?"));
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT route_path, title, item_link, author, pub_date, first_seen_at
                FROM feed_items
                WHERE route_path IN (%s) AND first_seen_at >= ?
                """.formatted(placeholders))) {
            for (int i = 0; i < routePaths.size(); i++) ps.setString(i + 1, routePaths.get(i));
            ps.setString(routePaths.size() + 1, firstSeenLowerBound(since));
            try (ResultSet rs = ps.executeQuery()) {
                return readNewItemsSince(rs, since);
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
            Instant firstSeen = Instant.parse(rs.getString("first_seen_at"));
            if (firstSeen.isBefore(since)) continue;
            result.add(new StoredItem(
                    rs.getString("route_path"), rs.getString("title"), rs.getString("item_link"),
                    rs.getString("author"), parseInstant(rs.getString("pub_date")), firstSeen));
        }
        result.sort(Comparator.comparing(StoredItem::firstSeenAt).reversed());
        return result;
    }

    /**
     * Returns all items first seen within {@code window} of now, newest first.
     * Capped at 500 rows so the reader stays fast regardless of volume.
     */
    public synchronized List<StoredItem> loadRecentItems(Duration window) throws SQLException {
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

    public synchronized UnreadPage loadUnreadPage(
            String source, String query, boolean bookmarkedOnly, Long beforeId, int limit)
            throws SQLException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Unread page limit must be between 1 and 100");
        }
        if (beforeId != null && beforeId <= 0) {
            throw new IllegalArgumentException("Unread page cursor must be positive");
        }
        String normalizedSource = source == null ? "" : source.strip();
        String normalizedQuery = query == null ? "" : query.strip();
        StringBuilder where = new StringBuilder(UNREAD_CONDITION);
        List<String> parameters = new ArrayList<>();
        if (!normalizedSource.isEmpty()) {
            where.append(" AND fi.route_path = ?");
            parameters.add(normalizedSource);
        }
        if (!normalizedQuery.isEmpty()) {
            where.append("""
                     AND (instr(lower(fi.title), lower(?)) > 0
                          OR instr(lower(fi.description), lower(?)) > 0
                          OR EXISTS (SELECT 1 FROM ai_summaries s
                                     WHERE s.item_link = fi.item_link
                                       AND instr(lower(s.summary), lower(?)) > 0))
                    """);
            parameters.add(normalizedQuery);
            parameters.add(normalizedQuery);
            parameters.add(normalizedQuery);
        }
        if (bookmarkedOnly) {
            where.append(" AND EXISTS (SELECT 1 FROM bookmarks b WHERE b.item_link = fi.item_link)");
        }

        long totalCount = countUnreadItems(where.toString(), parameters);
        List<String> sources = loadUnreadSources();
        String cursorClause = beforeId == null ? "" : " AND fi.id < ?";
        List<StoredItem> items = new ArrayList<>();
        List<String> bookmarkedLinks = new ArrayList<>();
        Long lastId = null;
        boolean hasMore = false;
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT fi.id, fi.route_path, fi.title, fi.item_link, fi.author, fi.pub_date, fi.first_seen_at,
                       EXISTS (SELECT 1 FROM bookmarks b WHERE b.item_link = fi.item_link) AS bookmarked
                FROM feed_items fi WHERE
                """ + where + cursorClause + " ORDER BY fi.id DESC LIMIT ?")) {
            int index = bindUnreadParameters(ps, parameters);
            if (beforeId != null) ps.setLong(index++, beforeId);
            ps.setInt(index, limit + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (items.size() == limit) {
                        hasMore = true;
                        break;
                    }
                    items.add(new StoredItem(
                            rs.getString("route_path"), rs.getString("title"), rs.getString("item_link"),
                            rs.getString("author"), parseInstant(rs.getString("pub_date")),
                            parseInstant(rs.getString("first_seen_at"))));
                    if (rs.getBoolean("bookmarked")) bookmarkedLinks.add(rs.getString("item_link"));
                    lastId = rs.getLong("id");
                }
            }
        }
        return new UnreadPage(items, bookmarkedLinks, totalCount, hasMore ? lastId : null, sources);
    }

    private long countUnreadItems(String where, List<String> parameters) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM feed_items fi WHERE " + where)) {
            bindUnreadParameters(ps, parameters);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private List<String> loadUnreadSources() throws SQLException {
        List<String> sources = new ArrayList<>();
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT DISTINCT fi.route_path FROM feed_items fi WHERE "
                     + UNREAD_CONDITION + " ORDER BY fi.route_path")) {
            while (rs.next()) sources.add(rs.getString("route_path"));
        }
        return sources;
    }

    private static int bindUnreadParameters(PreparedStatement ps, List<String> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) ps.setString(i + 1, parameters.get(i));
        return parameters.size() + 1;
    }

    /**
     * Returns items whose {@code first_seen_at} falls within the given calendar date
     * (system default timezone), ordered newest-first.
     */
    public synchronized List<StoredItem> loadItemsForDate(LocalDate date) throws SQLException {
        ZoneId tz = ZoneId.systemDefault();
        String from = date.atStartOfDay(tz).toInstant().toString();
        String to   = date.plusDays(1).atStartOfDay(tz).toInstant().toString();
        List<StoredItem> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT route_path, title, item_link, author, pub_date, first_seen_at
                FROM feed_items
                WHERE first_seen_at >= ? AND first_seen_at < ?
                ORDER BY first_seen_at DESC
                LIMIT 1000
                """)) {
            ps.setString(1, from);
            ps.setString(2, to);
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
    public synchronized int countItems(String routePath) throws SQLException {
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
    public synchronized List<StoredItem> browseItems(String routePath, int offset, int limit) throws SQLException {
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
    public synchronized List<FetchLogEntry> getRecentLogs(String routePath, int limit) throws SQLException {
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
    public synchronized List<FetchLogEntry> getAllRecentLogs(String routeFilter, int offset, int limit)
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
    public synchronized int countLogs(String routeFilter) throws SQLException {
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
    public synchronized int countLogsToday() throws SQLException {
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
    public synchronized int countErrorsToday() throws SQLException {
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
    public synchronized Instant lastFetchedAt(String routePath) throws SQLException {
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
    public synchronized List<FeedItem> mergeExtracts(List<FeedItem> items) throws SQLException {
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
    public synchronized String loadExtract(String itemLink) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT content FROM ai_extracts WHERE item_link=?")) {
            ps.setString(1, itemLink);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("content") : null;
            }
        }
    }

    // ── ai summaries ──────────────────────────────────────────────────────────

    public synchronized StoredArticle loadArticle(String link) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT fi.*, s.summary, e.content AS extract,
                       EXISTS (SELECT 1 FROM read_items r WHERE r.item_link = fi.item_link) AS is_read,
                       EXISTS (SELECT 1 FROM bookmarks b WHERE b.item_link = fi.item_link) AS is_bookmarked
                FROM feed_items fi
                LEFT JOIN ai_summaries s ON s.item_link = fi.item_link
                LEFT JOIN ai_extracts e ON e.item_link = fi.item_link
                WHERE fi.item_link = ?
                """)) {
            ps.setString(1, link);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                FeedItem item = new FeedItem(rs.getString("title"), rs.getString("item_link"),
                        rs.getString("description"), parseInstant(rs.getString("pub_date")),
                        rs.getString("author"), fromJson(rs.getString("categories")));
                return new StoredArticle(item, rs.getString("route_path"), parseInstant(rs.getString("first_seen_at")),
                        rs.getString("summary"), rs.getString("extract"), rs.getBoolean("is_read"),
                        rs.getBoolean("is_bookmarked"));
            }
        }
    }

    public synchronized FeedItem loadItem(String link) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT item_link, title, description, author, pub_date, categories
                FROM feed_items WHERE item_link = ?
                """)) {
            ps.setString(1, link);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new FeedItem(
                        rs.getString("title"),
                        rs.getString("item_link"),
                        rs.getString("description"),
                        parseInstant(rs.getString("pub_date")),
                        rs.getString("author"),
                        fromJson(rs.getString("categories"))) : null;
            }
        }
    }

    public synchronized boolean enqueueSummary(String link, Instant queuedAt, boolean retryFailed)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ai_summary_runs
                    (item_link, status, started_at, completed_at, duration_ms, model, error_detail)
                SELECT item_link, 'queued', ?, NULL, NULL, '', NULL
                FROM feed_items fi WHERE item_link = ?
                    AND NOT EXISTS (SELECT 1 FROM ai_summaries s
                        WHERE s.item_link = fi.item_link
                          AND trim(s.summary, char(9, 10, 11, 12, 13, 32)) <> '')
                ON CONFLICT(item_link) DO UPDATE SET
                    status = 'queued', started_at = excluded.started_at,
                    completed_at = NULL, duration_ms = NULL, model = '', error_detail = NULL
                WHERE ai_summary_runs.status = 'error' AND ?
                """)) {
            // SQLite compares queued timestamps as text; fixed precision preserves nanosecond order.
            ps.setString(1, QUEUE_TIME_FORMAT.format(queuedAt));
            ps.setString(2, link);
            ps.setBoolean(3, retryFailed);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized FeedItem claimNextSummary(Instant startedAt, String model) throws SQLException {
        String link;
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE ai_summary_runs
                SET status = 'running', started_at = ?, model = ?
                WHERE item_link = (
                    SELECT r.item_link FROM ai_summary_runs r
                    JOIN feed_items fi ON fi.item_link = r.item_link
                    WHERE r.status = 'queued'
                        AND NOT EXISTS (SELECT 1 FROM ai_summaries s
                            WHERE s.item_link = r.item_link
                              AND trim(s.summary, char(9, 10, 11, 12, 13, 32)) <> '')
                    ORDER BY r.started_at, r.rowid LIMIT 1)
                RETURNING item_link
                """)) {
            ps.setString(1, startedAt.toString());
            ps.setString(2, model);
            try (ResultSet rs = ps.executeQuery()) {
                link = rs.next() ? rs.getString("item_link") : null;
            }
        }
        return link == null ? null : loadItem(link);
    }

    public synchronized void recoverInterruptedSummaries(Instant now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
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

    /** Returns the cached summary for an article URL, or {@code null} if not cached. */
    public synchronized String loadSummary(String itemLink) throws SQLException {
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
    public synchronized java.util.Map<String, String> loadSummaries(List<String> links) throws SQLException {
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
        inTransaction(() -> {
            String now = Instant.now().toString();
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
                ps.setString(4, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("""
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
            refreshSearchIndex(List.of(itemLink));
            return null;
        });
    }

    /** Records that AI summarization has started for an article URL. */
    public synchronized void markSummaryStarted(String itemLink, Instant startedAt, String model)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ai_summary_runs
                    (item_link, status, started_at, completed_at, duration_ms, model, error_detail)
                VALUES (?, 'running', ?, NULL, NULL, ?, NULL)
                ON CONFLICT(item_link) DO UPDATE SET
                    status       = 'running',
                    started_at   = excluded.started_at,
                    completed_at = NULL,
                    duration_ms  = NULL,
                    model        = excluded.model,
                    error_detail = NULL
                """)) {
            ps.setString(1, itemLink);
            ps.setString(2, startedAt.toString());
            ps.setString(3, model);
            ps.executeUpdate();
        }
    }

    /** Records that AI summarization has finished for an article URL. */
    public synchronized void markSummaryFinished(
            String itemLink,
            Instant startedAt,
            Instant completedAt,
            String model,
            String errorDetail) throws SQLException {
        long durationMs = Duration.between(startedAt, completedAt).toMillis();
        try (PreparedStatement ps = conn.prepareStatement("""
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

    /** Batch-loads latest AI summarization status for a list of links. */
    public synchronized Map<String, SummaryStatus> loadSummaryStatuses(List<String> links) throws SQLException {
        Map<String, SummaryStatus> result = new LinkedHashMap<>();
        if (links.isEmpty()) return result;
        String placeholders = String.join(",", Collections.nCopies(links.size(), "?"));
        try (PreparedStatement ps = conn.prepareStatement("""
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
            try (PreparedStatement ps = conn.prepareStatement("""
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

    /** Returns the total number of cached AI summaries. */
    public synchronized int countSummaries() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM ai_summaries");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Returns the total number of cached AI extracts. */
    public synchronized int countExtracts() throws SQLException {
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
                INSERT INTO bookmarks (item_link, title, route_path, bookmarked_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(item_link) DO UPDATE SET title = excluded.title, route_path = excluded.route_path
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

    public synchronized boolean isBookmarked(String link) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM bookmarks WHERE item_link = ?")) {
            ps.setString(1, link);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Returns all bookmarked item links for initialising client-side state. */
    public synchronized List<String> loadBookmarkLinks() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT item_link FROM bookmarks ORDER BY bookmarked_at DESC")) {
            while (rs.next()) result.add(rs.getString("item_link"));
        }
        return result;
    }

    /** Returns full bookmark records newest first. */
    public synchronized List<BookmarkedItem> loadBookmarks() throws SQLException {
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
    public synchronized boolean isRead(String link) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM read_items WHERE item_link = ?")) {
            ps.setString(1, link);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Returns all read item links, newest first. */
    public synchronized List<String> loadReadLinks() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT item_link FROM read_items ORDER BY read_at DESC")) {
            while (rs.next()) result.add(rs.getString("item_link"));
        }
        return result;
    }

    /** Marks all supplied links as read atomically. */
    public synchronized void markAllRead(List<String> links) throws SQLException {
        if (links.isEmpty()) return;
        String now = Instant.now().toString();
        String values = String.join(",", Collections.nCopies(links.size(), "(?, ?)"));
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO read_items (item_link, read_at) VALUES " + values)) {
            int index = 1;
            for (String link : links) {
                ps.setString(index++, link);
                ps.setString(index++, now);
            }
            ps.executeUpdate();
        }
    }

    // ── search ────────────────────────────────────────────────────────────────

    /**
     * Full-text search across item titles, descriptions, and AI summaries.
     */
    public synchronized List<StoredItem> search(String query, int limit) throws SQLException {
        return searchHits(query, limit).stream().map(SearchHit::item).toList();
    }

    public synchronized List<SearchHit> searchHits(String query, int limit) throws SQLException {
        if (query == null || query.isBlank()) return List.of();
        if (limit < 1) return List.of();
        if (query.codePointCount(0, query.length()) > 200) {
            throw new IllegalArgumentException("Search queries must not exceed 200 characters");
        }
        List<String> terms = java.util.Arrays.stream(query.strip().split("(?U)\\s+"))
                .filter(term -> !term.isBlank()).distinct().toList();
        if (terms.isEmpty()) return List.of();
        List<String> literals = terms.stream().filter(FeedStore::isLiteralSearchTerm).toList();
        List<String> words = terms.stream().filter(term -> !isLiteralSearchTerm(term)).toList();
        List<String> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        if (!words.isEmpty()) {
            sql.append("""
                WITH word_matches AS (
                    SELECT rowid, bm25(feed_search_fts, 0, 8, 1, 3) AS rank
                    FROM feed_search_fts
                    WHERE feed_search_fts MATCH ?
                )
                """);
            parameters.add(toFtsQuery(String.join(" ", words)));
        }
        sql.append("""
                SELECT fi.route_path, fi.item_link, fi.title,
                       fi.author, fi.pub_date, fi.first_seen_at, fi.description, s.summary,
                       (0
                """);
        for (String term : literals) {
            sql.append(" + (instr(lower(coalesce(fi.title, '')), lower(?)) > 0)");
            parameters.add(term);
        }
        sql.append(") AS title_matches FROM feed_items fi ");
        if (!words.isEmpty()) sql.append("JOIN word_matches m ON m.rowid = fi.id ");
        sql.append("""
                LEFT JOIN ai_summaries s ON s.item_link = fi.item_link
                WHERE trim(fi.title, char(9, 10, 11, 12, 13, 32)) <> ''
                  AND trim(fi.item_link, char(9, 10, 11, 12, 13, 32)) <> ''
                """);
        for (String term : literals) {
            sql.append("""
                    AND (instr(lower(coalesce(fi.title, '')), lower(?)) > 0
                      OR instr(lower(coalesce(fi.description, '')), lower(?)) > 0
                      OR instr(lower(coalesce(s.summary, '')), lower(?)) > 0)
                    """);
            parameters.addAll(List.of(term, term, term));
        }
        sql.append("ORDER BY title_matches DESC, ");
        if (!words.isEmpty()) sql.append("m.rank, ");
        sql.append("fi.id DESC LIMIT ?");
        List<SearchHit> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int index = 1;
            for (String parameter : parameters) ps.setString(index++, parameter);
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StoredItem item = new StoredItem(
                            rs.getString("route_path"),
                            rs.getString("title"),
                            rs.getString("item_link"),
                            rs.getString("author"),
                            parseInstant(rs.getString("pub_date")),
                            parseInstant(rs.getString("first_seen_at")));
                    result.add(new SearchHit(item, rs.getString("description"), rs.getString("summary")));
                }
            }
        }
        return result;
    }

    private static boolean isLiteralSearchTerm(String term) {
        return term.codePoints().anyMatch(character ->
                Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN
                        || !Character.isLetterOrDigit(character));
    }

    private void initializeSearchIndex(boolean migratedItems) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS search_index_state (
                        id INTEGER PRIMARY KEY CHECK(id = 1),
                        version INTEGER NOT NULL
                    )
                    """);
        }
        boolean indexExists;
        try (Statement statement = conn.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'feed_search_fts'
                     """)) {
            indexExists = result.next();
        }
        try (Statement statement = conn.createStatement();
             ResultSet result = statement.executeQuery("SELECT version FROM search_index_state WHERE id = 1")) {
            if (!migratedItems && indexExists && result.next() && result.getInt(1) == SEARCH_INDEX_VERSION) {
                return;
            }
        }
        try (Statement statement = conn.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS feed_search_fts");
            statement.execute("""
                    CREATE VIRTUAL TABLE feed_search_fts
                    USING fts5(item_link UNINDEXED, title, description, summary)
                    """);
        }
        rebuildSearchIndex();
        try (PreparedStatement statement = conn.prepareStatement("""
                INSERT INTO search_index_state (id, version) VALUES (1, ?)
                ON CONFLICT(id) DO UPDATE SET version = excluded.version
                """)) {
            statement.setInt(1, SEARCH_INDEX_VERSION);
            statement.executeUpdate();
        }
    }

    private void rebuildSearchIndex() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("DELETE FROM feed_search_fts");
            s.execute("""
                    INSERT INTO feed_search_fts(rowid, item_link, title, description, summary)
                    SELECT fi.id, fi.item_link, fi.title, fi.description, s.summary
                    FROM feed_items fi
                    LEFT JOIN ai_summaries s ON s.item_link = fi.item_link
                    """);
        }
    }

    private void refreshSearchIndex(List<String> links) throws SQLException {
        List<String> valid = links.stream()
                .filter(link -> link != null && !link.isBlank())
                .distinct()
                .toList();
        if (valid.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(valid.size(), "?"));
        try (PreparedStatement delete = conn.prepareStatement(
                     "DELETE FROM feed_search_fts WHERE item_link IN (" + placeholders + ")");
             PreparedStatement insert = conn.prepareStatement("""
                     INSERT INTO feed_search_fts(rowid, item_link, title, description, summary)
                     SELECT fi.id, fi.item_link, fi.title, fi.description, s.summary
                     FROM feed_items fi
                     LEFT JOIN ai_summaries s ON s.item_link = fi.item_link
                     WHERE fi.item_link IN (%s)
                     """.formatted(placeholders))) {
            for (int i = 0; i < valid.size(); i++) {
                delete.setString(i + 1, valid.get(i));
                insert.setString(i + 1, valid.get(i));
            }
            delete.executeUpdate();
            insert.executeUpdate();
        }
    }

    private static String toFtsQuery(String query) {
        return java.util.Arrays.stream(query.strip().split("\\s+"))
                .filter(term -> !term.isBlank())
                .map(term -> "\"" + term.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(" "));
    }

    // ── health ────────────────────────────────────────────────────────────────

    /**
     * Returns health statistics for every route that has fetch history,
     * computed from the most recent 10 fetch attempts per route.
     * Results are ordered by most-recently-fetched first.
     */
    public synchronized Map<String, RouteHealth> loadRouteHealth() throws SQLException {
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
