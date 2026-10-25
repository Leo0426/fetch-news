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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite-backed store for feed items and fetch logs.
 *
 * <p>All database access is synchronized because statements on the shared connection
 * otherwise see in-progress transactions. WAL permits readers on separate connections.
 */
public class FeedStore implements Closeable {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};
    public static final int EXCERPT_MIN_LENGTH = 200;
    static final int EXCERPT_MAX_LENGTH = 300;
    final Connection conn;
    final ObjectMapper mapper;

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

    private final SummaryQueue summaryQueue = new SummaryQueue(this);
    private final PickStore picks = new PickStore(this);
    private final AccountStore accounts = new AccountStore(this);
    private final ReadingState reading = new ReadingState(this);
    private final RadarStore radar = new RadarStore(this);
    private final RadarJobs radarJobs = new RadarJobs(this);
    private final UnreadInbox unread = new UnreadInbox(this);
    private final FeedSchema schema = new FeedSchema(this);
    private final ItemQueries items = new ItemQueries(this);
    private final AiCache aiCache = new AiCache(this);
    private final SearchIndex searchIndex = new SearchIndex(this);
    private final FetchStateStore fetchState = new FetchStateStore(this);

    /** The durable AI summary queue; shares this store's connection and lock. */
    public SummaryQueue summaryQueue() {
        return summaryQueue;
    }

    /** Accounts and their credentials; shares this store's connection and lock. */
    public AccountStore accounts() {
        return accounts;
    }

    /** Each reader's bookmarks and read state; shares this store's connection and lock. */
    public ReadingState reading() {
        return reading;
    }

    /** Fetch history and refresh state: the fetch log, per-route health, HTTP feed snapshots and RSS refresh backoff; shares this store's connection and lock. */
    public FetchStateStore fetchState() {
        return fetchState;
    }

    /** The FTS5 search index over titles, descriptions and summaries, and the queries that read it; shares this store's connection and lock. */
    public SearchIndex searchIndex() {
        return searchIndex;
    }

    /** Cached AI output per article: generated summaries and extracted article text; shares this store's connection and lock. */
    public AiCache aiCache() {
        return aiCache;
    }

    /** Read-only article queries behind the reader, briefing, digests and admin item browsing; shares this store's connection and lock. */
    public ItemQueries items() {
        return items;
    }

    /** Creates and migrates the core tables; feature modules add their own tables through their createSchema; shares this store's connection and lock. */
    FeedSchema schema() {
        return schema;
    }

    /** The unread inbox: each reader's retained unread articles, filtered and paged by insertion order; shares this store's connection and lock. */
    public UnreadInbox unread() {
        return unread;
    }

    /** Radar runs, reports and community context; shares this store's connection and lock. */
    public RadarStore radar() {
        return radar;
    }

    /** Durable radar background jobs; shares this store's connection and lock. */
    public RadarJobs radarJobs() {
        return radarJobs;
    }

    /** Triage labels and readers' verdicts on them; shares this store's connection and lock. */
    public PickStore picks() {
        return picks;
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
            schema().initializeSchema();
            schema().initializeSourceSchema();
            return null;
        });
    }

    @FunctionalInterface
    interface SqlOperation<T> {
        T run() throws SQLException;
    }

    <T> T inTransaction(SqlOperation<T> operation) throws SQLException {
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

    static Instant parseInstant(String value) {
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
                    (route_path,item_link,title,description,author,pub_date,categories,first_seen_at,last_seen_at,image)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(item_link) DO UPDATE SET
                    title        = excluded.title,
                    description  = excluded.description,
                    author       = excluded.author,
                    pub_date     = excluded.pub_date,
                    categories   = excluded.categories,
                    last_seen_at = excluded.last_seen_at,
                    image        = coalesce(excluded.image, feed_items.image)
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
                ps.setString(10, item.image());
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
        searchIndex().refreshSearchIndex(valid.stream().map(FeedItem::link).toList());

        return valid.size() - existing;
    }

    /**
     * Gives stored items without a cover the first image or video poster of their description, the
     * same fallback fetches apply to new items. Returns how many items gained a cover.
     */
    public synchronized int backfillCoverImages() throws SQLException {
        Map<String, String> covers = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT item_link, description FROM feed_items
                WHERE image IS NULL AND (description LIKE '%<img%' OR description LIKE '%poster=%')
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String cover = app.support.CoverImages.firstInHtml(rs.getString("description"), rs.getString("item_link"));
                if (cover != null) covers.put(rs.getString("item_link"), cover);
            }
        }
        if (covers.isEmpty()) return 0;
        return inTransaction(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE feed_items SET image = ? WHERE item_link = ? AND image IS NULL")) {
                for (var cover : covers.entrySet()) {
                    ps.setString(1, cover.getValue());
                    ps.setString(2, cover.getKey());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return covers.size();
        });
    }

    /**
     * Returns the most recently seen items for a route (offline fallback).
     * Items with no link are never stored, so all returned items have a link.
     */
    public synchronized List<FeedItem> loadItems(String routePath) throws SQLException {
        List<FeedItem> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT item_link,title,description,author,pub_date,categories,image
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
                            fromJson(rs.getString("categories")),
                            rs.getString("image")));
                }
            }
        }
        return result;
    }

    /**
     * Builds a Feed from items stored in the DB for offline fallback.
     * Returns an empty Optional when no items have been saved for this path.
     */
    public synchronized Optional<Feed> offlineFeed(String routePath) throws SQLException {
        List<FeedItem> items = loadItems(routePath);
        if (items.isEmpty()) return Optional.empty();
        return Optional.of(new Feed(
                "【离线缓存】" + routePath,
                routePath,
                "离线缓存 · 共 " + items.size() + " 条",
                items));
    }

    private static final List<String> ITEM_COLUMNS =
            List.of("route_path", "title", "item_link", "author", "pub_date", "first_seen_at", "image");

    /** Column list read by {@link #readStoredItem}, optionally qualified with a table alias such as {@code "fi."}. */
    static String itemColumns(String alias) {
        return String.join(", ", ITEM_COLUMNS.stream().map(column -> alias + column).toList());
    }

    static String selectItems(String fromClause) {
        return "SELECT " + itemColumns("") + "\n" + fromClause;
    }

    static StoredItem readStoredItem(ResultSet rs) throws SQLException {
        return new StoredItem(rs.getString("route_path"), rs.getString("title"), rs.getString("item_link"),
                rs.getString("author"), parseInstant(rs.getString("pub_date")),
                parseInstant(rs.getString("first_seen_at")), rs.getString("image"));
    }

    // ── ai summaries ──────────────────────────────────────────────────────────

    public synchronized StoredArticle loadArticle(long userId, String link) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT fi.*, s.summary, e.content AS extract,
                       EXISTS (SELECT 1 FROM read_items r
                               WHERE r.user_id = ? AND r.item_link = fi.item_link) AS is_read,
                       EXISTS (SELECT 1 FROM bookmarks b
                               WHERE b.user_id = ? AND b.item_link = fi.item_link) AS is_bookmarked
                FROM feed_items fi
                LEFT JOIN ai_summaries s ON s.item_link = fi.item_link
                LEFT JOIN ai_extracts e ON e.item_link = fi.item_link
                WHERE fi.item_link = ?
                """)) {
            ps.setLong(1, userId);
            ps.setLong(2, userId);
            ps.setString(3, link);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                FeedItem item = new FeedItem(rs.getString("title"), rs.getString("item_link"),
                        rs.getString("description"), parseInstant(rs.getString("pub_date")),
                        rs.getString("author"), fromJson(rs.getString("categories")), rs.getString("image"));
                return new StoredArticle(item, rs.getString("route_path"), parseInstant(rs.getString("first_seen_at")),
                        rs.getString("summary"), rs.getString("extract"), rs.getBoolean("is_read"),
                        rs.getBoolean("is_bookmarked"));
            }
        }
    }

    public synchronized FeedItem loadItem(String link) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT item_link, title, description, author, pub_date, categories, image
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
                        fromJson(rs.getString("categories")),
                        rs.getString("image")) : null;
            }
        }
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
