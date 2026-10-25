package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import app.store.FeedStore;
import app.store.ItemPick;
import app.store.PickKind;
import app.store.StoredItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReaderItemsTest {
    private static final String LONG = "长".repeat(FeedStore.EXCERPT_MIN_LENGTH);

    @TempDir Path directory;

    @Test
    void buildsCardsWithSummaryOrExcerptCoverAndTheReadersLabels() throws Exception {
        try (FeedStore store = new FeedStore(directory.resolve("feed.db"), new ObjectMapper())) {
            store.saveItems("/news", List.of(
                    new FeedItem("Summarized", "https://example.com/a", "<p>" + LONG + "</p>", null, null, List.of(),
                            "https://cdn.example.com/a.jpg"),
                    new FeedItem("Excerpted", "https://example.com/b", "<p>" + LONG + "</p>", null, null, List.of())));
            store.aiCache().saveSummary("https://example.com/a", "AI 摘要", "m");
            store.picks().saveTriage("时事要闻", List.of("https://example.com/b"),
                    Map.of("https://example.com/b", new ItemPick("影响大", null)), "m", Instant.now());
            store.picks().setFeedback(7, "https://example.com/b", PickKind.MUST_KNOW, "up", Instant.now());
            List<StoredItem> stored = new ArrayList<>(store.items().loadRecentItems(Duration.ofHours(1)));
            stored.add(new StoredItem("/news", " ", "https://example.com/untitled", null, null, Instant.now(), null));

            ReaderItems.Page page = new ReaderItems(store).load(stored, 7);

            assertEquals(2, page.items().size(), "articles without a title are skipped");
            ReaderItem summarized = card(page, "https://example.com/a");
            ReaderItem excerpted = card(page, "https://example.com/b");
            assertEquals("AI 摘要", summarized.summary());
            assertNull(summarized.excerpt(), "a summary replaces the excerpt");
            assertEquals("https://cdn.example.com/a.jpg", summarized.image());
            assertEquals(LONG, excerpted.excerpt());
            assertEquals("影响大", page.picks().label("https://example.com/b").mustKnowReason());
            assertEquals("up", page.picks().verdict("https://example.com/b", "must"));
            assertEquals(1, page.picks().agreed());
        }
    }

    @Test
    void bareCardsKeepArticlesWithoutAiData() {
        var page = ReaderItems.bare(List.of(
                new StoredItem("/news", "Title", "https://example.com/a", null, null, Instant.now(), null),
                new StoredItem("/news", "Title", "", null, null, Instant.now(), null)));

        assertEquals(1, page.items().size());
        assertNull(page.items().getFirst().summary());
        assertTrue(page.picks().isEmpty());
    }

    private static ReaderItem card(ReaderItems.Page page, String link) {
        return page.items().stream().filter(item -> item.link().equals(link)).findFirst().orElseThrow();
    }
}
