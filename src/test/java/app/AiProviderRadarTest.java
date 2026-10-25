package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import app.ai.AiConfig;
import app.radar.RadarTestEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiProviderRadarTest {
    @TempDir
    Path directory;

    @Test
    void bothRemoteProtocolsRunRadarWithoutPersistingCredentials() throws Exception {
        for (String protocol : List.of("openai", "anthropic")) {
            Path root = directory.resolve(protocol);
            Files.createDirectories(root);
            try (var env = new RadarTestEnvironment(root, "{\"enabled\":true}")) {
                env.ai.save(new AiConfig(
                        "http://127.0.0.1:" + env.server.getAddress().getPort() + "/v1",
                        "remote",
                        true,
                        protocol,
                        "DO-NOT-PERSIST",
                        2048,
                        true,
                        "max_tokens"));
                env.save("https://example.test/article");
                var run = env.service.submit(
                        LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "Asia/Shanghai", protocol);
                assertEquals(
                        "",
                        env.store.radar().loadRun(run.runId()).request().ai().apiKey());
                env.worker.processNext();
                assertEquals("success", env.service.status(run.runId()).state());
                assertFalse(env.store.radar().loadMarkdown(run.runId()).contains("DO-NOT-PERSIST"));
            }
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
