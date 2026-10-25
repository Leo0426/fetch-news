package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
            byte[] body = mapper.writeValueAsBytes(Map.of("response", input.has("format") ? "{}" : "ordinary"));
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
            assertThrows(ExecutionException.class, () -> waiting.get(5,TimeUnit.SECONDS));
            assertEquals(1,requests.get());
            configs.save(expected);
            assertEquals("{}",client.generateJson("json",expected).text());
            configs.save(new AiConfig(expected.host(),expected.model(),false));
            assertThrows(IllegalStateException.class, () -> client.generate("disabled"));
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void interactiveWaitersGoBeforeBackgroundOnesAndEachPriorityKeepsArrivalOrder() throws Exception {
        var gate = new AiGenerationGate();
        var order = new CopyOnWriteArrayList<String>();
        var running = gate.acquire(AiGenerationGate.Priority.BACKGROUND);
        Thread firstBackground = waitFor(gate, AiGenerationGate.Priority.BACKGROUND, "background-1", order);
        Thread secondBackground = waitFor(gate, AiGenerationGate.Priority.BACKGROUND, "background-2", order);
        Thread interactive = waitFor(gate, AiGenerationGate.Priority.INTERACTIVE, "interactive", order);

        running.close();
        for (Thread thread : List.of(firstBackground, secondBackground, interactive)) thread.join(5000);

        assertEquals(List.of("interactive", "background-1", "background-2"), order);
    }

    /** Starts a thread that queues at the gate and returns once it is blocked waiting. */
    private static Thread waitFor(AiGenerationGate gate, AiGenerationGate.Priority priority, String name,
                                  List<String> order) throws InterruptedException {
        Thread thread = Thread.ofPlatform().start(() -> {
            try (var permit = gate.acquire(priority)) {
                order.add(name);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(Thread.State.WAITING, thread.getState());
        return thread;
    }
}
