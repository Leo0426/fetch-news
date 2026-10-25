package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.core.FeedItem;
import app.store.RacingSummaryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SummaryProgressRaceTest {
    @TempDir Path directory;

    @Test
    void completionBetweenProgressReadsNeverReturnsSuccessWithoutSummary() throws Exception {
        var mapper = new ObjectMapper();
        try (var store = RacingSummaryStore.completingWith(directory.resolve("feed.db"), mapper, "Just completed");
             var service = new ArticleSummarizer(null, store, null, null)) {
            String link = "https://example.com/item";
            store.saveItems("/articles", List.of(new FeedItem("Title", link, "body", null, null, List.of())));
            assertEquals(new SummaryProgress("success", "Just completed", null), service.progress(link));
        }
    }
}
