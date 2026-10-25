package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.ai.AiConfig;
import app.ai.AiConfigStore;
import app.ai.ArticleExtractor;
import app.ai.ArticleSummarizer;
import app.ai.OllamaClient;
import app.core.FeedItem;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
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
            var reader = new ReaderSummaryResource(store, service, config);
            var initial = reader.summaryStatus(link);
            assertEquals("none", initial.getBody().state());
            assertEquals("no-store", initial.getHeaders().getFirst("Cache-Control"));
            var submitted = reader.summarize(link, "untrusted title");
            assertEquals(202, submitted.getStatusCode().value());
            assertEquals("no-store", submitted.getHeaders().getFirst("Cache-Control"));
            assertEquals("queued", reader.summaryStatus(link).getBody().state());
            assertEquals(202, reader.summarize(link, "another title").getStatusCode().value());
            assertEquals(404, reader.summarize("https://example.com/unknown", "").getStatusCode().value());
            assertEquals(400, reader.summarize("", "").getStatusCode().value());
            store.aiCache().saveSummary(link, "Cached", "fixture");
            assertEquals(200, reader.summarize(link, "").getStatusCode().value());
            config.save(new AiConfig(null, null, false));
            var disabled = reader.summarize(link, "");
            assertEquals(400, disabled.getStatusCode().value());
            assertEquals("no-store", disabled.getHeaders().getFirst("Cache-Control"));
            assertTrue(disabled.getBody().error().contains("AI Provider"));
            assertFalse(disabled.getBody().error().contains("Ollama"));
            assertEquals("Cached", reader.summaryStatus(link).getBody().summary());
            assertEquals(503, new ReaderSummaryResource(null, service, config)
                    .summaryStatus(link).getStatusCode().value());
            store.close();
            assertEquals(500, reader.summaryStatus(link).getStatusCode().value());
        }
    }

    @Test
    void failureStatusUsesProviderGuidanceWithoutExposingStoredException() throws Exception {
        var mapper = new ObjectMapper();
        var config = new AiConfigStore(directory.resolve("ai.json"), mapper);
        String link = "https://example.com/failed";
        String secret = "sk-fixture-secret-must-not-be-rendered";
        try (var store = new FeedStore(directory.resolve("feed.db"), mapper);
             var service = new ArticleSummarizer(null, store, null, config)) {
            store.saveItems("/articles", List.of(new FeedItem("Saved", link, "body", null, null, List.of())));
            store.summaryQueue().markFinished(link, Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:00:01Z"), "fixture",
                    "Provider rejected Authorization: Bearer " + secret);
            var reader = new ReaderSummaryResource(store, service, config);
            var response = reader.summaryStatus(link);
            assertEquals(200, response.getStatusCode().value());
            assertEquals("error", response.getBody().state());
            assertTrue(response.getBody().error().contains("AI Provider"));
            assertFalse(response.getBody().error().contains("本地模型"));
            assertFalse(response.getBody().error().contains(secret));
            assertFalse(response.getBody().error().contains("Authorization"));
            assertEquals("error", store.summaryQueue().statuses(List.of(link)).get(link).status());
        }
    }
}
