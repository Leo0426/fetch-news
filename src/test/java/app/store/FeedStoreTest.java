package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
            store.aiCache().saveSummary("https://example.com/model", "Ollama 本地模型 摘要", "test-model");

            assertEquals("https://example.com/vector", store.searchIndex().search("Vector", 10).getFirst().link());
            assertEquals("https://example.com/morning", store.searchIndex().search("Embedding", 10).getFirst().link());
            assertEquals("https://example.com/model", store.searchIndex().search("本地模型", 10).getFirst().link());
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
            assertEquals(1, store.searchIndex().search("obsolete", 10).size());

            store.saveItems("/articles", List.of(
                    item("Fresh title", "https://example.com/item", "renewed marker")));

            assertTrue(store.searchIndex().search("obsolete", 10).isEmpty());
            assertEquals("https://example.com/item", store.searchIndex().search("renewed", 10).getFirst().link());
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

            store.saveItems("/articles", List.of(item("Item", link, "Body")));
            store.summaryQueue().enqueue(link, startedAt.minusSeconds(1), false);
            store.summaryQueue().claimNext(startedAt, "test-model");
            SummaryStatus running = store.summaryQueue().statuses(List.of(link)).get(link);

            assertTrue(running.running());
            assertEquals(startedAt, running.startedAt());

            store.summaryQueue().markFinished(link, startedAt, completedAt, "test-model", null);
            SummaryStatus finished = store.summaryQueue().statuses(List.of(link)).get(link);

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

            store.aiCache().saveSummary(link, "摘要", "test-model");
            SummaryStatus status = store.summaryQueue().statuses(List.of(link)).get(link);

            assertTrue(status.success());
            assertEquals(0L, status.durationMs());
        }
    }

    @Test
    void keepsKnownCoverWhenRefetchHasNone() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            store.saveItems("/articles", List.of(
                    item("Story", "https://example.com/story", "Body").withImage("https://cdn.example.com/a.jpg")));
            store.saveItems("/articles", List.of(item("Story", "https://example.com/story", "Body")));

            assertEquals("https://cdn.example.com/a.jpg",
                    store.items().loadRecentItems(Duration.ofHours(1)).getFirst().image());
            assertEquals("https://cdn.example.com/a.jpg", store.loadItem("https://example.com/story").image());
        }
    }

    @Test
    void excerptsOnlyLongDescriptionsAsPlainText() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            String body = "长".repeat(FeedStore.EXCERPT_MIN_LENGTH);
            store.saveItems("/articles", List.of(
                    item("Long", "https://example.com/long", "<p><b>" + body + "</b></p>"),
                    item("Short", "https://example.com/short", "<p>" + "短".repeat(150) + "</p>" + "<i></i>".repeat(20))));

            var excerpts = store.items().loadExcerpts(List.of("https://example.com/long", "https://example.com/short"));

            assertEquals(body, excerpts.get("https://example.com/long"));
            assertEquals(1, excerpts.size());
        }
    }

    @Test
    void pickFeedbackFeedsCategoryExamplesAndPerReaderCounts() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            Instant now = Instant.parse("2026-10-24T08:00:00Z");
            store.saveItems("/articles", List.of(
                    item("Quake", "https://example.com/quake", "x"),
                    item("Anniversary", "https://example.com/anniversary", "x"),
                    item("Elsewhere", "https://example.com/elsewhere", "x")));
            store.picks().saveTriage("时事要闻", List.of("https://example.com/quake", "https://example.com/anniversary"),
                    Map.of("https://example.com/quake", new ItemPick("灾情", null),
                            "https://example.com/anniversary", new ItemPick("纪念", null)), "m", now);
            store.picks().saveTriage("科技资讯", List.of("https://example.com/elsewhere"),
                    Map.of("https://example.com/elsewhere", new ItemPick("别的", null)), "m", now);
            store.picks().setFeedback(1, "https://example.com/quake", PickKind.MUST_KNOW, "up", now);
            store.picks().setFeedback(1, "https://example.com/anniversary", PickKind.MUST_KNOW, "down", now);
            store.picks().setFeedback(2, "https://example.com/anniversary", PickKind.MUST_KNOW, "down", now);
            store.picks().setFeedback(1, "https://example.com/elsewhere", PickKind.MUST_KNOW, "down", now);
            store.picks().setFeedback(1, "https://example.com/old", PickKind.MUST_KNOW, "up", now.minusSeconds(86400 * 40));

            var examples = store.picks().feedbackExamples("时事要闻", now.minusSeconds(60), 5);

            assertEquals(List.of(
                    new PickFeedback.Example(PickKind.MUST_KNOW, "down", "Anniversary"),
                    new PickFeedback.Example(PickKind.MUST_KNOW, "up", "Quake")), examples);
            assertEquals(List.of(1, 2), Arrays.stream(store.picks().countFeedback(1, now.minusSeconds(60)))
                    .boxed().toList());
            assertEquals("down", store.picks().feedback(2, List.of("https://example.com/anniversary"))
                    .get("https://example.com/anniversary").verdict(PickKind.MUST_KNOW));
        }
    }

    @Test
    void backfillsCoversFromStoredDescriptionsOnce() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            store.saveItems("/articles", List.of(
                    item("Pictured", "https://example.com/a/1", "<p>x</p><img src='cover.jpg'>"),
                    item("Pixel only", "https://example.com/a/2", "<img src='https://t.example.com/p.gif' width='1'>"),
                    item("Has cover", "https://example.com/a/3", "<img src='other.jpg'>")
                            .withImage("https://cdn.example.com/kept.jpg"),
                    item("Text", "https://example.com/a/4", "<p>no image</p>")));

            assertEquals(1, store.backfillCoverImages());
            assertEquals(0, store.backfillCoverImages());
            assertEquals("https://example.com/a/cover.jpg", store.loadItem("https://example.com/a/1").image());
            assertEquals(null, store.loadItem("https://example.com/a/2").image());
            assertEquals("https://cdn.example.com/kept.jpg", store.loadItem("https://example.com/a/3").image());
        }
    }

    private static FeedItem item(String title, String link, String description) {
        return new FeedItem(title, link, description, Instant.parse("2026-05-22T08:00:00Z"), null, List.of());
    }
}
