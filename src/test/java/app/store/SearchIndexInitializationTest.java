package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.auth.UserAccount;
import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchIndexInitializationTest {
    @TempDir
    Path tempDir;

    @Test
    void reopeningCurrentIndexPreservesSearchWithoutRewritingIndex() throws Exception {
        Path database = tempDir.resolve("feed.db");
        String link = "https://example.test/article";
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.saveItems("/articles", List.of(new FeedItem(
                    "Vector notes", link, "Embedding 本地模型", Instant.parse("2026-10-05T00:00:00Z"),
                    "Author", List.of("Research"))));
            store.aiCache().saveSummary(link, "Inference summary", "fixture");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER reject_index_insert BEFORE INSERT ON feed_search_fts_content
                    BEGIN SELECT RAISE(ABORT, 'index must be reused'); END
                    """);
            statement.execute("""
                    CREATE TRIGGER reject_index_delete BEFORE DELETE ON feed_search_fts_content
                    BEGIN SELECT RAISE(ABORT, 'index must be reused'); END
                    """);
        }

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            for (String query : List.of("Vector", "Embedding", "Inference", "模型 Embedding")) {
                assertEquals(List.of(link), store.searchIndex().search(query, 10).stream().map(StoredItem::link).toList());
            }
        }
        assertEquals(2, value(database, """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'trigger' AND name IN ('reject_index_insert', 'reject_index_delete')
                """));
    }

    @Test
    void initializesLegacyArticlesAndSummariesWithoutAnIndexOrVersion() throws Exception {
        Path database = tempDir.resolve("legacy.db");
        createLegacyDatabase(database, false);

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            for (String query : List.of("Canonical", "winnerword", "cachedsummary", "模型 winnerword")) {
                assertEquals(List.of("https://example.test/one"), matches(store, query));
            }
            assertEquals("Cachedsummary 模型摘要", store.aiCache().loadSummary("https://example.test/one"));
        }
        assertEquals(1, value(database, "SELECT version FROM search_index_state WHERE id = 1"));
    }

    @Test
    void rebuildsAnOutdatedIndexFromCanonicalArticlesAndSummaries() throws Exception {
        Path database = tempDir.resolve("outdated.db");
        seedCurrentIndex(database);
        execute(database,
                "UPDATE feed_items SET description = 'renewed 模型'",
                "UPDATE ai_summaries SET summary = 'freshsummary 推理'",
                "UPDATE search_index_state SET version = 0 WHERE id = 1");

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertEquals(List.of("https://example.test/article"), matches(store, "renewed 模型"));
            assertEquals(List.of("https://example.test/article"), matches(store, "freshsummary 推理"));
            assertTrue(store.searchIndex().search("obsolete", 10).isEmpty());
            assertTrue(store.searchIndex().search("oldsummary", 10).isEmpty());
        }
        assertEquals(1, value(database, "SELECT version FROM search_index_state WHERE id = 1"));
    }

    @Test
    void restoresMissingIndexEvenWhenVersionIsCurrent() throws Exception {
        Path database = tempDir.resolve("missing.db");
        seedCurrentIndex(database);
        execute(database, "DROP TABLE feed_search_fts");

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertEquals(List.of("https://example.test/article"), matches(store, "obsolete 模型"));
            assertEquals(List.of("https://example.test/article"), matches(store, "oldsummary"));
        }
        assertEquals(1, value(database, "SELECT version FROM search_index_state WHERE id = 1"));
    }

    @Test
    void linkDeduplicationRebuildsIndexUsingNewIdsAndFirstSourceOwnership() throws Exception {
        Path database = tempDir.resolve("dedup.db");
        createLegacyDatabase(database, true);
        execute(database,
                """
                CREATE VIRTUAL TABLE feed_search_fts
                USING fts5(item_link UNINDEXED, title, description, summary)
                """,
                """
                INSERT INTO feed_search_fts(rowid, item_link, title, description, summary)
                SELECT fi.id, fi.item_link, fi.title, fi.description, s.summary
                FROM feed_items fi LEFT JOIN ai_summaries s ON s.item_link = fi.item_link
                """,
                "CREATE TABLE search_index_state (id INTEGER PRIMARY KEY CHECK(id = 1), version INTEGER NOT NULL)",
                "INSERT INTO search_index_state VALUES (1, 1)");

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            StoredArticle article = store.loadArticle(UserAccount.OWNER_ID, "https://example.test/one");
            assertEquals("/first", article.routePath());
            assertEquals("Canonical title", article.item().title());
            assertEquals(Instant.parse("2026-10-01T00:00:00Z"), article.firstSeenAt());
            assertEquals(List.of("https://example.test/one"), matches(store, "winnerword cachedsummary"));
            assertEquals(List.of("https://example.test/two"), matches(store, "otherword"));
            assertTrue(store.searchIndex().search("discardword", 10).isEmpty());
        }
        assertEquals(2, value(database, "SELECT COUNT(*) FROM feed_search_fts"));
        assertEquals(0, value(database, """
                SELECT COUNT(*) FROM feed_search_fts f
                LEFT JOIN feed_items i ON i.id = f.rowid AND i.item_link = f.item_link
                WHERE i.id IS NULL
                """));
        assertEquals(1, value(database, """
                SELECT COUNT(*) FROM feed_items
                WHERE item_link = 'https://example.test/one' AND last_seen_at = '2026-10-04T00:00:00Z'
                """));
    }

    @Test
    void failedVersionWriteRollsBackRebuildAndAllowsCompleteRetry() throws Exception {
        Path database = tempDir.resolve("failed.db");
        seedCurrentIndex(database);
        execute(database,
                "UPDATE feed_items SET description = 'renewed 模型'",
                "UPDATE search_index_state SET version = 0 WHERE id = 1",
                """
                CREATE TRIGGER reject_version BEFORE INSERT ON search_index_state
                BEGIN SELECT RAISE(ABORT, 'version unavailable'); END
                """);

        assertThrows(SQLException.class, () -> {
            try (FeedStore ignored = new FeedStore(database, new ObjectMapper())) {
            }
        });
        assertEquals(0, value(database, "SELECT version FROM search_index_state WHERE id = 1"));
        assertEquals(1, value(database, "SELECT COUNT(*) FROM feed_search_fts WHERE feed_search_fts MATCH 'obsolete'"));
        assertEquals(0, value(database, "SELECT COUNT(*) FROM feed_search_fts WHERE feed_search_fts MATCH 'renewed'"));
        execute(database, "DROP TRIGGER reject_version");

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertEquals(List.of("https://example.test/article"), matches(store, "renewed 模型"));
            assertEquals(List.of("https://example.test/article"), matches(store, "oldsummary"));
            assertTrue(store.searchIndex().search("obsolete", 10).isEmpty());
        }
        assertEquals(1, value(database, "SELECT version FROM search_index_state WHERE id = 1"));
    }

    private static void seedCurrentIndex(Path database) throws Exception {
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.saveItems("/articles", List.of(new FeedItem(
                    "Article title", "https://example.test/article", "obsolete 模型", null, null, List.of())));
            store.aiCache().saveSummary("https://example.test/article", "oldsummary", "fixture");
        }
    }

    private static void createLegacyDatabase(Path database, boolean duplicateLinks) throws SQLException {
        execute(database,
                """
                CREATE TABLE feed_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, route_path TEXT NOT NULL, item_link TEXT NOT NULL,
                    title TEXT, description TEXT, author TEXT, pub_date TEXT, categories TEXT,
                    first_seen_at TEXT NOT NULL, last_seen_at TEXT NOT NULL, %s
                )
                """.formatted(duplicateLinks ? "UNIQUE(route_path, item_link)" : "UNIQUE(item_link)"),
                """
                CREATE TABLE ai_summaries (
                    item_link TEXT PRIMARY KEY, summary TEXT NOT NULL, model TEXT NOT NULL, created_at TEXT NOT NULL
                )
                """,
                """
                INSERT INTO feed_items
                    (id, route_path, item_link, title, description, first_seen_at, last_seen_at)
                VALUES (11, '/first', 'https://example.test/one', 'Canonical title', 'winnerword 模型',
                        '2026-10-01T00:00:00Z', '2026-10-02T00:00:00Z'),
                       (58, '/second', 'https://example.test/two', 'Other title', 'otherword',
                        '2026-10-02T00:00:00Z', '2026-10-02T00:00:00Z')
                """,
                """
                INSERT INTO ai_summaries VALUES
                    ('https://example.test/one', 'Cachedsummary 模型摘要', 'fixture', '2026-10-02T00:00:00Z')
                """);
        if (duplicateLinks) {
            execute(database, """
                    INSERT INTO feed_items
                        (id, route_path, item_link, title, description, first_seen_at, last_seen_at)
                    VALUES (27, '/second', 'https://example.test/one', 'Duplicate title', 'discardword',
                            '2026-10-03T00:00:00Z', '2026-10-04T00:00:00Z')
                    """);
        }
    }

    private static void execute(Path database, String... sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            for (String query : sql) statement.execute(query);
        }
    }

    private static long value(Path database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static List<String> matches(FeedStore store, String query) throws SQLException {
        return store.searchIndex().search(query, 10).stream().map(StoredItem::link).toList();
    }
}
