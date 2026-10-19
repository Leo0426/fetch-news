package app.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiGenerationGateTest {
    @TempDir Path directory;
    @Test void serializesGenerationAndRejectsChangedOrDisabledConfigAfterWaiting() throws Exception {
        var mapper = new ObjectMapper();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/generate", e -> {
            requests.incrementAndGet();
            var input = mapper.readTree(e.getRequestBody());
            entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            byte[] body = mapper.writeValueAsBytes(java.util.Map.of("response", input.has("format") ? "{}" : "ordinary"));
            e.sendResponseHeaders(200,body.length);
            try (var out = e.getResponseBody()) { out.write(body); }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); server.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var configs = new AiConfigStore(directory.resolve("ai.json"),mapper);
            var expected = new AiConfig("http://127.0.0.1:"+server.getAddress().getPort(),"first",true);
            configs.save(expected);
            var client = new OllamaClient(configs,mapper);
            var first = executor.submit(() -> client.generate("first"));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var waiting = executor.submit(() -> client.generateJson("second",expected));
            configs.save(new AiConfig(expected.host(),"second",true));
            release.countDown();
            assertEquals("ordinary",first.get(5,TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> waiting.get(5,TimeUnit.SECONDS));
            assertEquals(1,requests.get());
            configs.save(expected);
            assertEquals("{}",client.generateJson("json",expected).text());
            configs.save(new AiConfig(expected.host(),expected.model(),false));
            assertThrows(IllegalStateException.class, () -> client.generate("disabled"));
        } finally { release.countDown(); server.stop(0); }
    }
}
