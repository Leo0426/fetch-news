package app.radar;

import app.ai.AiConfig;
import app.ai.AiConfigStore;
import app.ai.OllamaClient;
import app.core.FeedItem;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class RadarTestEnvironment implements AutoCloseable {
    public final ObjectMapper mapper = new ObjectMapper();
    public final FeedStore store;
    public final AiConfigStore ai;
    public final RadarConfig config;
    public final BuiltInProfiles profiles;
    public final RadarService service;
    public final RadarWorker worker;
    public final HttpServer server;
    public final AtomicInteger requests = new AtomicInteger();
    public volatile String blocksResponse = "{\"blocks\":{\"summary\":[\"摘要\"],\"importance\":[\"价值\"]}}";
    public volatile Runnable onBlocks = () -> {};
    public volatile String scoreResponse = "{\"score\":8,\"reason\":\"Useful\",\"summary\":\"Evidence\",\"tags\":[]}";

    public volatile Function<String, String> scoring = p -> scoreResponse;

    public RadarTestEnvironment(Path directory, String configJson) throws Exception {
        store = new FeedStore(directory.resolve("feed.db"), mapper);
        ai = new AiConfigStore(directory.resolve("ai.json"), mapper);
        config = mapper.readValue(configJson, RadarConfig.class);
        profiles = new BuiltInProfiles(mapper);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            requests.incrementAndGet();
            var input = mapper.readTree(e.getRequestBody());
            String prompt = prompt(input);
            String output = prompt.startsWith("TASK: score")
                    ? scoring.apply(prompt)
                    : prompt.startsWith("TASK: dedup")
                            ? "{\"match\":\"distinct\"}"
                            : prompt.startsWith("TASK: route") ? "{\"profile\":\"tech-news\"}" : blocksResponse;
            if (prompt.startsWith("你是一位新闻编辑")) output = "新闻摘要已生成。";
            if (prompt.startsWith("TASK: blocks")) onBlocks.run();
            Object envelope = response(e.getRequestURI().getPath(), output);
            byte[] body = mapper.writeValueAsBytes(envelope);
            e.sendResponseHeaders(200, body.length);
            try (var out = e.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        ai.save(new AiConfig("http://127.0.0.1:" + server.getAddress().getPort(), "fake", true));
        var client = new OllamaClient(ai, mapper);
        service = new RadarService(store, config, profiles, ai, () -> Map.of("/a", "AI"));
        worker = new RadarWorker(store, config, ai, client, null, mapper, Clock.systemUTC());
    }

    private static String prompt(JsonNode input) {
        if (input.has("messages")) return input.at("/messages/0/content").asText();
        if (input.has("contents")) return input.at("/contents/0/parts/0/text").asText();
        return input.path(input.has("input") ? "input" : "prompt").asText();
    }

    private static Object response(String path, String output) {
        if (path.endsWith("/messages")) {
            return Map.of("content", List.of(Map.of("type", "text", "text", output)), "stop_reason", "end_turn");
        }
        if (path.endsWith("/chat/completions")) {
            return Map.of("choices", List.of(Map.of("message", Map.of("content", output), "finish_reason", "stop")));
        }
        if (path.endsWith("/responses")) {
            var block = Map.of("type", "output_text", "text", output);
            return Map.of("status", "completed", "output", List.of(
                    Map.of("type", "message", "status", "completed", "content", List.of(block))));
        }
        if (path.endsWith(":generateContent")) {
            var content = Map.of("role", "model", "parts", List.of(Map.of("text", output)));
            return Map.of("candidates", List.of(Map.of("content", content, "finishReason", "STOP")));
        }
        return Map.of("response", output, "done", true);
    }

    public void save(String link) throws Exception {
        store.saveItems(
                "/a",
                List.of(new FeedItem(
                        "Technical event " + link,
                        link,
                        "Concrete evidence and engineering details. ".repeat(20),
                        null,
                        null,
                        List.of())));
    }

    @Override
    public void close() throws Exception {
        worker.close();
        server.stop(0);
        store.close();
    }
}
