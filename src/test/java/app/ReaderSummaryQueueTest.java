package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.ai.AiConfig;
import app.ai.AiConfigStore;
import app.ai.ArticleExtractor;
import app.ai.ArticleSummarizer;
import app.ai.OllamaClient;
import app.core.FeedItem;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReaderSummaryQueueTest {
    @TempDir Path directory;

    @Test
    void submissionReturnsImmediatelyAndStatusReadsNeverTriggerWork() throws Exception {
        var mapper = new ObjectMapper();
        var config = new AiConfigStore(directory.resolve("ai.json"), mapper);
        config.save(new AiConfig("http://127.0.0.1:1", "fixture", true));
        var model = new OllamaClient(config, mapper) {
            @Override public Generation generateWithMetadata(String prompt) {
                throw new AssertionError("A controller request must not start inference");
            }
        };
        try (var store = new FeedStore(directory.resolve("feed.db"), mapper);
             var service = new ArticleSummarizer(model, store, new ArticleExtractor(model, store,
                     url -> { throw new AssertionError("No article request expected"); }), config)) {
            String link = "https://example.com/saved";
            store.saveItems("/articles", List.of(new FeedItem("Saved", link, "body", null, null, List.of())));
            var reader = new ReaderResource(store, null, null, service, config);
            assertEquals("none", reader.summaryStatus(link).getBody().state());
            assertEquals(202, reader.summarize(link, "untrusted title").getStatusCode().value());
            assertEquals("queued", reader.summaryStatus(link).getBody().state());
            assertEquals(202, reader.summarize(link, "another title").getStatusCode().value());
            assertEquals(404, reader.summarize("https://example.com/unknown", "").getStatusCode().value());
            assertEquals(400, reader.summarize("", "").getStatusCode().value());
            store.saveSummary(link, "Cached", "fixture");
            assertEquals(200, reader.summarize(link, "").getStatusCode().value());
            config.save(new AiConfig(null, null, false));
            assertEquals(400, reader.summarize(link, "").getStatusCode().value());
            assertEquals("Cached", reader.summaryStatus(link).getBody().summary());
            assertEquals(503, new ReaderResource(null, null, null, service, config)
                    .summaryStatus(link).getStatusCode().value());
            store.close();
            assertEquals(500, reader.summaryStatus(link).getStatusCode().value());
        }
    }
}
