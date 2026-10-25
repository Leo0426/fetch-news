package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArticleSummarizerTest {
    @TempDir Path directory;

    @Test
    void processesCanonicalSavedBodyOnceWithoutFetchingArticle() throws Exception {
        var mapper = new ObjectMapper();
        var config = new AiConfigStore(directory.resolve("ai.json"), mapper);
        config.save(new AiConfig("http://127.0.0.1:1", "configured-model", true));
        var prompts = new ArrayList<String>();
        var model = new OllamaClient(config, mapper) {
            @Override public Generation generateWithMetadata(String prompt) {
                prompts.add(prompt);
                return new Generation("本地摘要", "actual-model");
            }
        };
        var fetches = new AtomicInteger();
        try (var store = new FeedStore(directory.resolve("feed.db"), mapper);
             var service = new ArticleSummarizer(model, store, new ArticleExtractor(model, store, url -> {
                 fetches.incrementAndGet();
                 return "unused";
             }), config)) {
            var item = new FeedItem("Saved title", "https://example.com/article", "<p>正文材料。".repeat(60), null, null, List.of());
            store.saveItems("/articles", List.of(item));
            assertEquals("queued", service.request(item.link()).state());
            service.request(item.link());
            assertEquals(0, service.enqueue(List.of(item)));
            service.processNext();
            service.processNext();
            assertEquals("本地摘要", store.aiCache().loadSummary(item.link()));
            assertEquals(1, prompts.size());
            assertTrue(prompts.getFirst().contains("Saved title"));
            assertTrue(prompts.getFirst().contains("正文材料"));
            assertEquals(0, fetches.get());
            assertEquals("actual-model", store.summaryQueue().statuses(List.of(item.link())).get(item.link()).model());
            assertEquals("success", service.progress(item.link()).state());
        }
    }
}
