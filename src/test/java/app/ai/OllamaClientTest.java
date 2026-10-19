package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OllamaClientTest {
    @TempDir Path directory;

    @Test
    void responseKeepsModelUsedAtRequestTimeWhenConfigurationChanges() throws Exception {
        var config = new AiConfigStore(directory.resolve("ai.json"), new ObjectMapper());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            exchange.getRequestBody().readAllBytes();
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] body = "{\"response\":\"摘要\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            String host = "http://127.0.0.1:" + server.getAddress().getPort();
            config.save(new AiConfig(host, "first", true));
            var client = new OllamaClient(config, new ObjectMapper());
            var result = executor.submit(() -> client.generateWithMetadata("test"));
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
            config.save(new AiConfig(host, "second", true));
            release.countDown();
            var generated = result.get(5, TimeUnit.SECONDS);
            assertEquals("摘要", generated.text());
            assertEquals("first", generated.model());
            assertEquals(null, generated.metrics().inputTokens());
            org.junit.jupiter.api.Assertions.assertTrue(generated.metrics().elapsedMillis() >= 0);
            config.save(new AiConfig(host, "second", false));
            assertThrows(IllegalStateException.class, () -> client.generateWithMetadata("disabled"));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
