package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import app.ai.AiConfig;
import app.ai.ArticleSummarizer;
import app.ai.OllamaClient;
import app.radar.RadarTestEnvironment;
import app.reader.ReaderSummaryResource;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AiProviderRadarTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"ollama,auto", "openai,chat", "openai,responses", "anthropic,auto", "gemini,auto"})
    void selectedModelGeneratesReaderSummaryAndRadarWithoutPersistingCredentials(String protocol, String apiStyle) throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}")) {
            String host = "http://127.0.0.1:" + env.server.getAddress().getPort() + "/v1";
            var config = new AiConfig(host, "custom-model", true, protocol, "DO-NOT-PERSIST", 2048, true,
                    "auto", apiStyle, Map.of("temperature", 0.5));
            env.ai.save(config);
            String link = "https://example.test/article";
            env.save(link);
            try (var summaries = new ArticleSummarizer(new OllamaClient(env.ai, env.mapper), env.store, null, env.ai)) {
                var reader = new ReaderSummaryResource(env.store, summaries, env.ai);
                assertEquals(202, reader.summarize(link, "").getStatusCode().value());
                summaries.processNext();
                var progress = reader.summaryStatus(link).getBody();
                assertEquals("success", progress.state());
                assertEquals("新闻摘要已生成。", progress.summary());
            }
            var run = env.service.submit(
                    LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "Asia/Shanghai", protocol);
            var snapshot = env.store.radar().loadRun(run.runId()).request().ai();
            assertEquals("", snapshot.apiKey());
            assertEquals(config.identity(), snapshot.identity());
            env.worker.processNext();
            assertEquals("success", env.service.status(run.runId()).state());
            assertFalse(env.store.radar().loadMarkdown(run.runId()).contains("DO-NOT-PERSIST"));
        }
    }

    @Test
    void switchingProtocolsSeparatesAnalysisCacheEvenAtSameHostAndModel() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}")) {
            String host = "http://127.0.0.1:" + env.server.getAddress().getPort();
            env.ai.save(new AiConfig(host, "same-model", true));
            env.save("https://example.test/article");
            String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
            env.service.submit(date, "Asia/Shanghai", "local");
            env.worker.processNext();
            assertEquals(2, env.requests.get());
            env.ai.save(new AiConfig(host, "same-model", true, "openai", "fake-key", 4096, true, "max_tokens"));
            var remote = env.service.submit(date, "Asia/Shanghai", "remote");
            env.worker.processNext();
            assertEquals("success", env.service.status(remote.runId()).state());
            assertEquals(4, env.requests.get());
        }
    }

    @Test
    void invalidStructuredResponseCannotPutCredentialsIntoDurableRunErrors() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}")) {
            String key = "SECRET_MARKER";
            env.ai.save(new AiConfig(
                    "http://127.0.0.1:" + env.server.getAddress().getPort() + "/v1",
                    "remote",
                    true,
                    "openai",
                    key,
                    2048,
                    false,
                    "max_tokens"));
            env.scoreResponse = key;
            env.save("https://example.test/article");
            var run = env.service.submit(
                    LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "Asia/Shanghai", "invalid");
            env.worker.processNext();
            assertEquals("partial", env.service.status(run.runId()).state());
            assertFalse(env.mapper
                    .writeValueAsString(env.store.radar().loadRun(run.runId()))
                    .contains(key));
            assertFalse(env.mapper
                    .writeValueAsString(env.store.radar().loadReport(run.runId()))
                    .contains(key));
        }
    }
}
