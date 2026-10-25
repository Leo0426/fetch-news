package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import app.core.FetchClient;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SummaryWorkerTest {
    @TempDir
    Path directory;

    @Test
    void concurrentEntrypointsCannotStartAnotherPipelineUntilFirstCompletes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var fixture = new Fixture(
                        prompt -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                            return new OllamaClient.Generation("摘要", "fixture");
                        },
                        url -> {
                            throw new AssertionError("Saved body must be reused");
                        });
                var executor = Executors.newSingleThreadExecutor()) {
            fixture.save("first", body());
            fixture.save("second", body());
            fixture.service.request(link("first"));
            fixture.service.request(link("second"));
            var task = executor.submit(fixture.service::processNext);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("running", fixture.service.request(link("first")).state());
            assertEquals("queued", fixture.service.progress(link("second")).state());
            fixture.service.processNext();
            assertEquals(1, calls.get());
            release.countDown();
            task.get(5, TimeUnit.SECONDS);
            fixture.service.processNext();
            assertEquals(2, calls.get());
            assertEquals("success", fixture.service.progress(link("second")).state());
        } finally {
            release.countDown();
        }
    }

    @Test
    void shortInputFetchesAndExtractsOnceAndExplicitRetryReusesExtract() throws Exception {
        var fetches = new AtomicInteger();
        var calls = new AtomicInteger();
        try (var fixture = new Fixture(
                prompt -> {
                    int attempt = calls.incrementAndGet();
                    if (attempt == 1) return new OllamaClient.Generation("已抽取的正文", "extract-model");
                    if (attempt == 2) throw new RuntimeException();
                    assertTrue(prompt.contains("已抽取的正文"));
                    return new OllamaClient.Generation("重试摘要", "summary-model");
                },
                url -> {
                    fetches.incrementAndGet();
                    return "<body>" + body() + "</body>";
                })) {
            var item = fixture.save("short", "短描述");
            fixture.service.request(item.link());
            fixture.service.processNext();
            assertEquals("error", fixture.service.progress(item.link()).state());
            assertFalse(fixture.store
                    .summaryQueue()
                    .statuses(List.of(item.link()))
                    .get(item.link())
                    .errorDetail()
                    .isBlank());
            assertEquals(0, fixture.service.enqueue(List.of(item)));
            fixture.service.processNext();
            assertEquals(2, calls.get());
            fixture.service.request(item.link());
            fixture.service.processNext();
            assertEquals("重试摘要", fixture.store.aiCache().loadSummary(item.link()));
            assertEquals(1, fetches.get());
            assertEquals(3, calls.get());
        }
    }

    @Test
    void disabledAiPausesQueuedWorkAndUnknownOrCachedRequestsDoNotGenerate() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new Fixture(
                prompt -> {
                    calls.incrementAndGet();
                    return new OllamaClient.Generation("摘要", "changed-model");
                },
                url -> {
                    throw new AssertionError("No HTTP expected");
                })) {
            fixture.save("waiting", body());
            assertThrows(NoSuchElementException.class, () -> fixture.service.request(link("unknown")));
            fixture.service.request(link("waiting"));
            fixture.config.save(new AiConfig(null, "changed-model", false));
            fixture.service.processNext();
            assertEquals("queued", fixture.service.progress(link("waiting")).state());
            assertEquals(0, calls.get());
            fixture.config.save(new AiConfig(null, "changed-model", true));
            fixture.service.processNext();
            fixture.service.request(link("waiting"));
            fixture.service.processNext();
            assertEquals(1, calls.get());
            assertEquals(
                    "changed-model",
                    fixture.store
                            .summaryQueue()
                            .statuses(List.of(link("waiting")))
                            .get(link("waiting"))
                            .model());
        }
    }

    @Test
    void emptySummaryOrMissingTextFailsWithoutFallbackInference() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new Fixture(
                prompt -> {
                    calls.incrementAndGet();
                    return new OllamaClient.Generation(" \n", "fixture");
                },
                url -> "<html><script>ignored</script></html>")) {
            fixture.save("empty-summary", body());
            fixture.save("empty-article", "");
            fixture.service.request(link("empty-summary"));
            fixture.service.request(link("empty-article"));
            fixture.service.processNext();
            fixture.service.processNext();
            assertEquals(
                    "error", fixture.service.progress(link("empty-summary")).state());
            assertEquals(
                    "error", fixture.service.progress(link("empty-article")).state());
            assertNull(fixture.store.aiCache().loadSummary(link("empty-summary")));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void shutdownInterruptsRunningTaskAndLeavesNextQueued() throws Exception {
        var entered = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var fixture = new Fixture(
                prompt -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    new CountDownLatch(1).await();
                    throw new AssertionError("Unreachable");
                },
                url -> "unused")) {
            fixture.save("active", body());
            fixture.save("waiting", body());
            fixture.service.request(link("active"));
            fixture.service.request(link("waiting"));
            fixture.service.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            fixture.service.close();
            assertEquals("error", fixture.service.progress(link("active")).state());
            assertEquals("queued", fixture.service.progress(link("waiting")).state());
            assertEquals(1, calls.get());
            assertThrows(IllegalStateException.class, () -> fixture.service.request(link("waiting")));
        }
    }

    @Test
    void shutdownDuringArticleFetchCannotStartExtractionModel() throws Exception {
        var entered = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var fixture = new Fixture(
                prompt -> {
                    calls.incrementAndGet();
                    return new OllamaClient.Generation("unexpected", "fixture");
                },
                url -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "<article>" + body() + "</article>";
                })) {
            fixture.save("short", "short");
            fixture.service.request(link("short"));
            fixture.service.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            fixture.service.close();
            assertEquals(0, calls.get());
            assertEquals("error", fixture.service.progress(link("short")).state());
        }
    }

    @Test
    void promptLimitsDoNotSplitSupplementaryUnicodeCharacters() throws Exception {
        var prompts = new ArrayList<String>();
        try (var fixture = new Fixture(
                prompt -> {
                    prompts.add(prompt);
                    return new OllamaClient.Generation("摘要", "fixture");
                },
                url -> "unused")) {
            fixture.save("unicode", "𠀀".repeat(3001));
            fixture.service.request(link("unicode"));
            fixture.service.processNext();
            assertTrue(prompts.getFirst().contains("𠀀".repeat(3000)));
            assertFalse(prompts.getFirst().contains("𠀀".repeat(3001)));
            assertEquals(
                    prompts.getFirst(),
                    new String(prompts.getFirst().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        }
    }

    private static String body() {
        return "完整的文章内容。".repeat(60);
    }

    private static String link(String name) {
        return "https://fixture.invalid/" + name;
    }

    private interface Generate {
        OllamaClient.Generation run(String prompt) throws Exception;
    }

    private class Fixture implements AutoCloseable {
        final AiConfigStore config;
        final FeedStore store;
        final ArticleSummarizer service;

        Fixture(Generate generate, FetchClient fetch) throws Exception {
            var mapper = new ObjectMapper();
            config = new AiConfigStore(directory.resolve("ai.json"), mapper);
            config.save(new AiConfig("http://127.0.0.1:1", "fixture", true));
            store = new FeedStore(directory.resolve("feed.db"), mapper);
            var model = new OllamaClient(config, mapper) {
                @Override
                public Generation generateWithMetadata(String prompt) throws Exception {
                    return generate.run(prompt);
                }
            };
            service = new ArticleSummarizer(model, store, new ArticleExtractor(model, store, fetch), config);
        }

        FeedItem save(String name, String description) throws Exception {
            var item = new FeedItem(name, link(name), description, null, null, List.of());
            store.saveItems("/articles", List.of(item));
            return item;
        }

        @Override
        public void close() throws Exception {
            service.close();
            store.close();
        }
    }
}
