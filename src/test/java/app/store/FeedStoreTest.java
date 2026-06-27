package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for SQLite-backed feed persistence and search.
 */
class FeedStoreTest {
    @TempDir
    Path tempDir;

    /**
     * Verifies FTS search covers title, description, and cached AI summary text.
     */
    @Test
    void searchUsesTitleDescriptionAndSummaryIndex() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            store.saveItems("/articles", List.of(
                    item("Vector search", "https://example.com/vector", "Index design notes"),
                    item("Morning links", "https://example.com/morning", "Embedding pipeline walkthrough"),
                    item("Model notes", "https://example.com/model", "Release checklist")));
            store.saveSummary("https://example.com/model", "Ollama 本地模型 摘要", "test-model");

            assertEquals("https://example.com/vector", store.search("Vector", 10).getFirst().link());
            assertEquals("https://example.com/morning", store.search("Embedding", 10).getFirst().link());
            assertEquals("https://example.com/model", store.search("本地模型", 10).getFirst().link());
        }
    }

    /**
     * Verifies item upserts refresh FTS rows instead of leaving stale text searchable.
     */
    @Test
    void saveItemsRefreshesSearchIndexForExistingLinks() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            store.saveItems("/articles", List.of(
                    item("Old title", "https://example.com/item", "obsolete marker")));
            assertEquals(1, store.search("obsolete", 10).size());

            store.saveItems("/articles", List.of(
                    item("Fresh title", "https://example.com/item", "renewed marker")));

            assertTrue(store.search("obsolete", 10).isEmpty());
            assertEquals("https://example.com/item", store.search("renewed", 10).getFirst().link());
        }
    }

    /**
     * Verifies AI summarization start and duration are recorded per item.
     */
    @Test
    void recordsSummaryStartAndDuration() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            String link = "https://example.com/item";
            Instant startedAt = Instant.parse("2026-05-22T08:00:00Z");
            Instant completedAt = Instant.parse("2026-05-22T08:00:03.250Z");

            store.markSummaryStarted(link, startedAt, "test-model");
            SummaryStatus running = store.loadSummaryStatuses(List.of(link)).get(link);

            assertTrue(running.running());
            assertEquals(startedAt, running.startedAt());

            store.markSummaryFinished(link, startedAt, completedAt, "test-model", null);
            SummaryStatus finished = store.loadSummaryStatuses(List.of(link)).get(link);

            assertTrue(finished.success());
            assertEquals(3250L, finished.durationMs());
        }
    }

    /**
     * Verifies saved summaries are visible as successful summary status records.
     */
    @Test
    void saveSummaryCreatesDisplayableStatusWhenNoRunExists() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            String link = "https://example.com/summary";

            store.saveSummary(link, "摘要", "test-model");
            SummaryStatus status = store.loadSummaryStatuses(List.of(link)).get(link);

            assertTrue(status.success());
            assertEquals(0L, status.durationMs());
        }
    }

    private static FeedItem item(String title, String link, String description) {
        return new FeedItem(title, link, description, Instant.parse("2026-05-22T08:00:00Z"), null, List.of());
    }
}
