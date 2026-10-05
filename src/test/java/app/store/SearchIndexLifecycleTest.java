package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchIndexLifecycleTest {
    @TempDir
    Path tempDir;

    @Test
    void failedIndexUpdateRollsBackTheWholeArticleBatchAndAllowsRetry() throws Exception {
        Path database = tempDir.resolve("search.db");
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.saveItems("/first", List.of(item("original", "Old title", "obsolete 模型")));
            StoredArticle original = store.loadArticle(link("original"));
            failIndexInsert(database, "added");

            assertThrows(SQLException.class, () -> store.saveItems("/second", List.of(
                    item("original", "New title", "renewed 模型"),
                    item("added", "Added title", "additional 模型"))));
            assertEquals(original, store.loadArticle(link("original")));
            assertNull(store.loadArticle(link("added")));
            assertEquals(List.of(link("original")), matches(store, "obsolete 模型"));
            assertTrue(store.search("renewed", 10).isEmpty());
            store.saveBookmark(link("original"), "Old title", "/first");
        }
        execute(database, "DROP TRIGGER fail_index_insert");
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertEquals(List.of(link("original")), matches(store, "obsolete"));
            assertNull(store.loadArticle(link("added")));
            assertTrue(store.isBookmarked(link("original")));
            assertEquals(1, store.saveItems("/second", List.of(
                    item("original", "New title", "renewed 模型"),
                    item("added", "Added title", "additional 模型"))));
            assertTrue(store.search("obsolete", 10).isEmpty());
            assertEquals("/first", store.search("renewed 模型", 10).getFirst().routePath());
            assertEquals(List.of(link("added")), matches(store, "additional"));
        }
    }

    @Test
    void failedSummaryIndexUpdateLeavesNoSummaryOrSuccessStatusAndAllowsRetry() throws Exception {
        Path database = tempDir.resolve("summary.db");
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.saveItems("/articles", List.of(item("summary", "Model notes", "original 模型")));
            failIndexInsert(database, "summary");

            assertThrows(SQLException.class, () -> store.saveSummary(link("summary"), "inference 推理", "test"));
            assertNull(store.loadSummary(link("summary")));
            assertFalse(store.loadSummaryStatuses(List.of(link("summary"))).containsKey(link("summary")));
            assertEquals(List.of(link("summary")), matches(store, "original 模型"));
            assertTrue(store.search("inference", 10).isEmpty());

            execute(database, "DROP TRIGGER fail_index_insert");
            store.saveSummary(link("summary"), "inference 推理", "test");
            assertEquals("inference 推理", store.loadSummary(link("summary")));
            assertTrue(store.loadSummaryStatuses(List.of(link("summary"))).get(link("summary")).success());
            assertEquals(List.of(link("summary")), matches(store, "inference 推理"));
        }
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertEquals(List.of(link("summary")), matches(store, "inference 推理"));
        }
    }

    @Test
    void failedReplacementRetainsThePreviousSummaryAndStatusAcrossRestart() throws Exception {
        Path database = tempDir.resolve("replacement.db");
        StoredArticle original;
        SummaryStatus originalStatus;
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.saveItems("/articles", List.of(item("summary", "Model notes", "模型")));
            store.saveSummary(link("summary"), "obsolete 显卡", "previous");
            original = store.loadArticle(link("summary"));
            originalStatus = store.loadSummaryStatuses(List.of(link("summary"))).get(link("summary"));
            failIndexInsert(database, "summary");

            assertThrows(SQLException.class, () -> store.saveSummary(link("summary"), "renewed 推理", "next"));
            assertEquals(original, store.loadArticle(link("summary")));
            assertEquals(originalStatus, store.loadSummaryStatuses(List.of(link("summary"))).get(link("summary")));
            assertEquals(List.of(link("summary")), matches(store, "obsolete 显卡"));
            assertTrue(store.search("renewed", 10).isEmpty());
        }
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertEquals(original, store.loadArticle(link("summary")));
            assertEquals(originalStatus, store.loadSummaryStatuses(List.of(link("summary"))).get(link("summary")));
            assertEquals(List.of(link("summary")), matches(store, "obsolete 显卡"));
            execute(database, "DROP TRIGGER fail_index_insert");
            store.saveSummary(link("summary"), "renewed 推理", "next");
            assertEquals(List.of(link("summary")), matches(store, "renewed 推理"));
            assertTrue(store.search("obsolete", 10).isEmpty());
        }
    }

    private static void failIndexInsert(Path database, String slug) throws SQLException {
        // SQLite virtual tables cannot have triggers; inject the failure at their storage boundary.
        execute(database, """
                CREATE TRIGGER fail_index_insert BEFORE INSERT ON feed_search_fts_content
                WHEN NEW.c0 = '%s'
                BEGIN SELECT RAISE(ABORT, 'index unavailable'); END
                """.formatted(link(slug)));
    }

    private static void execute(Path database, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> matches(FeedStore store, String query) throws SQLException {
        return store.search(query, 10).stream().map(StoredItem::link).toList();
    }

    private static String link(String slug) {
        return "https://example.test/" + slug;
    }

    private static FeedItem item(String slug, String title, String description) {
        return new FeedItem(title, link(slug), description, null, null, List.of());
    }
}
