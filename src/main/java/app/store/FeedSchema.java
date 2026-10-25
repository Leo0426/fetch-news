package app.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates and migrates the core tables; feature modules add their own tables through their createSchema.
 */
public final class FeedSchema {
    private final FeedStore store;

    FeedSchema(FeedStore store) {
        this.store = store;
    }

    void initializeSourceSchema() throws SQLException {
        try (Statement s = store.conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS feed_item_sources(item_link TEXT NOT NULL, route_path TEXT NOT NULL, first_seen_at TEXT NOT NULL, last_seen_at TEXT NOT NULL, PRIMARY KEY(item_link,route_path))");
            s.execute("INSERT OR IGNORE INTO feed_item_sources SELECT item_link,route_path,first_seen_at,last_seen_at FROM feed_items");
            RadarStore.createSchema(s);
            RadarJobs.createSchema(s);
        }
    }

    void initializeSchema() throws SQLException {
        // Migration check must run before CREATE TABLE IF NOT EXISTS so that the
        // old schema (UNIQUE on route_path+item_link) is replaced before any new
        // rows are written under the new global-dedup constraint.
        boolean migratedItems = hasOldFeedItemsSchema();
        if (migratedItems) {
            try (Statement s = store.conn.createStatement()) {
                migrateToGlobalLinkDedup(s);
            }
        }
        try (Statement s = store.conn.createStatement()) {
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
                        image         TEXT,
                        UNIQUE(item_link)
                    )""");
            addColumnIfMissing(s, "feed_items", "image", "TEXT");
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
            SummaryQueue.createSchema(s);
            PickStore.createSchema(s);
            AccountStore.createSchema(s);
            ReadingState.createSchema(s);
            // Prune read state for items no longer in the feed store (keeps table small)
            s.execute("""
                    DELETE FROM read_items
                    WHERE item_link NOT IN (SELECT item_link FROM feed_items)""");
        }
        store.searchIndex().initializeSearchIndex(migratedItems);
    }

    static boolean addColumnIfMissing(Statement s, String table, String column, String type)
            throws SQLException {
        try (ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) return false;
            }
        }
        s.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        return true;
    }

    private boolean hasOldFeedItemsSchema() throws SQLException {
        try (PreparedStatement ps = store.conn.prepareStatement(
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
}
