package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiCapabilityGenerationTest {
    private static final String RESPONSES = """
            {"status":"completed","error":null,"output":[{"type":"reasoning","summary":[]},
            {"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}],
            "usage":{"input_tokens":23,"output_tokens":5}}
            """;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    @TempDir Path directory;
    private HttpServer server;
    private String host;
    private AiConfigStore store;
    private OllamaClient client;
    private volatile Function<Request, Reply> handler;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        host = "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy";
        store = new AiConfigStore(directory.resolve("ai.json"), mapper);
        client = new OllamaClient(store, mapper);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void learnsResponsesAndJsonPromptFallbackPerModelAndCredential() throws Exception {
        handler = request -> {
            if (request.path().endsWith("/chat/completions")) return new Reply(404, "route not found");
            if (request.body().at("/text/format").isObject())
                return new Reply(400, """
                        {"error":{"code":"unsupported_parameter","param":"text.format","message":"private error"}}
                        """);
            return new Reply(200, RESPONSES);
        };
        store.save(config("openai", "private-model", "auto", Map.of("verbosity", "low", "reasoning_effort", "low")));
        var first = client.generateJson("JSON task", null);
        assertEquals("done", first.text());
        assertEquals(23, first.metrics().inputTokens());
        assertEquals(5, first.metrics().outputTokens());
        assertEquals(3, requests.size());
        JsonNode body = requests.getLast().body();
        assertEquals("/proxy/responses", requests.getLast().path());
        assertEquals(1234, body.path("max_output_tokens").intValue());
        assertEquals("low", body.at("/text/verbosity").textValue());
        assertEquals("low", body.at("/reasoning/effort").textValue());
        assertEquals("private-model", body.path("model").textValue());
        assertFalse(body.path("store").booleanValue());
        assertTrue(body.path("input").textValue().contains("valid JSON object"));

        client.generateJson("another prompt", null);
        assertEquals(4, requests.size());
        assertEquals("/proxy/responses", requests.getLast().path());
        var previous = store.get();
        store.save(new AiConfig(host, previous.model(), true, "openai", "rotated", 1234, true, "auto",
                "auto", previous.parameters()));
        client.generateJson("new key", null);
        assertEquals(7, requests.size());
        store.save(store.get().withModel("other-model"));
        client.generateJson("new model", null);
        assertEquals(10, requests.size());
    }

    @Test
    void negotiatesOnlyTheWrongInterfaceAndPreservesActionableErrors() throws Exception {
        store.save(config("openai", "model", "auto", Map.of()));
        Reply wrongChat = new Reply(400, """
                {"error":{"type":"invalid_request_error","message":"Model is not supported in v1/chat/completions."}}
                """);
        handler = request -> request.path().endsWith("/chat/completions") ? wrongChat : new Reply(404, "not found");
        assertEquals("openai returned HTTP 400 [endpoint]",
                assertThrows(AiProviderFailure.class, () -> client.generate("test")).getMessage());
        assertEquals(2, requests.size());
        handler = request -> request.path().endsWith("/chat/completions") ? wrongChat : new Reply(401, "denied");
        assertTrue(assertThrows(AiProviderFailure.class, () -> client.generate("test")).getMessage()
                .startsWith("openai returned HTTP 401"));
        assertEquals(4, requests.size());
        handler = request -> request.path().endsWith("/chat/completions") ? wrongChat : new Reply(200, RESPONSES);
        assertEquals("done", client.generate("test"));
        assertEquals(6, requests.size());
        assertEquals("/proxy/responses", requests.getLast().path());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Model is not a chat model and not supported in v1/chat/completions. Use v1/completions.",
        "This model only supports embeddings.",
        "Model not found"
    })
    void unavailableOrNonTextModelsDoNotTriggerInterfaceNegotiation(String message) {
        store.save(config("openai", "model", "auto", Map.of()));
        handler = request -> new Reply(404, message);
        assertThrows(AiProviderFailure.class, () -> client.generate("test"));
        assertEquals(1, requests.size());
    }

    @Test
    void pinnedApiAndExplicitSamplingErrorAreNeverSilentlyChanged() {
        handler = request -> new Reply(404, "route not found");
        store.save(config("openai", "selected", "chat", Map.of()));
        assertThrows(IllegalStateException.class, () -> client.generate("a"));
        assertEquals(1, requests.size());

        handler = request -> new Reply(400, """
                {"error":{"code":"unsupported_parameter","param":"temperature","message":"private details"}}
                """);
        store.save(config("openai", "selected", "auto", Map.of("temperature", 0.7)));
        assertThrows(IllegalStateException.class, () -> client.generate("b"));
        assertEquals(2, requests.size());
        assertEquals(0.7, requests.getLast().body().get("temperature").doubleValue());
    }

    @Test
    void geminiListsTextModelsAndSendsNativeOptionsWithoutLeakingThinking() throws Exception {
        store.save(config("gemini", "models/new-model", "auto",
                Map.of("thinkingConfig", Map.of("thinkingBudget", 128), "topK", 8)));
        handler = request -> {
            if (request.path().equals("/proxy/models")) return new Reply(200, """
                    {"models":[{"name":"models/embed","supportedGenerationMethods":["embedContent"]}],
                    "nextPageToken":"p 2"}
                    """);
            if (request.path().startsWith("/proxy/models?")) return new Reply(200, """
                    {"models":[{"name":"models/new-model","supportedGenerationMethods":["generateContent"]}]}
                    """);
            return new Reply(200, """
                    {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"private","thought":true},
                    {"text":"answer"}]}}],"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":3}}
                    """);
        };
        var status = client.checkConnection();
        assertTrue(status.modelAvailable());
        assertEquals(List.of("new-model"), status.availableModels());
        assertEquals(2, requests.size());
        var generated = client.generateJson("task", null);
        assertEquals("answer", generated.text());
        assertEquals(12, generated.metrics().inputTokens());
        Request generation = requests.getLast();
        assertEquals("/proxy/models/new-model:generateContent", generation.path());
        assertEquals("fake-key", generation.geminiKey());
        assertEquals(8, generation.body().at("/generationConfig/topK").intValue());
        assertEquals(128, generation.body().at("/generationConfig/thinkingConfig/thinkingBudget").intValue());
        assertEquals(1234, generation.body().at("/generationConfig/maxOutputTokens").intValue());
        assertEquals("application/json", generation.body().at("/generationConfig/responseMimeType").textValue());
    }

    @Test
    void draftProbeRunsWhileDisabledAndVerifiesActualOutput() throws Exception {
        assertFalse(store.get().enabled());
        var draft = config("openai", "draft", "responses", Map.of()).withEnabled(false);
        handler = request -> new Reply(200, RESPONSES.replace("done", "{\\\"ok\\\":true}"));
        client.verifyGeneration(draft);
        assertEquals("draft", requests.getFirst().body().get("model").textValue());
        assertFalse(store.get().enabled());
        handler = request -> new Reply(200, RESPONSES.replace("done", "{\\\"ok\\\":\\\"true\\\"}"));
        assertEquals("AI platform returned unexpected verification content",
                assertThrows(IllegalStateException.class, () -> client.verifyGeneration(draft)).getMessage());
        String duplicate = mapper.writeValueAsString("{\"ok\":false,\"ok\":true}");
        handler = request -> new Reply(200, RESPONSES.replace("\"done\"", duplicate));
        assertEquals("AI platform returned unexpected verification content",
                assertThrows(IllegalStateException.class, () -> client.verifyGeneration(draft)).getMessage());
        assertEquals(3, requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}",
        "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"summary\":[{\"text\":\"private\"}]}]}",
        "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\"}]}]}",
        "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"x\"}]}"
    })
    void rejectsIncompleteOrNonTextResponsesWithoutRetry(String response) {
        handler = request -> new Reply(200, response);
        store.save(config("openai", "model", "responses", Map.of()));
        assertThrows(IllegalStateException.class, () -> client.generate("prompt"));
        assertEquals(1, requests.size());
    }

    @Test
    void ollamaSendsOptionsAndTokenCapAndRejectsTruncation() {
        store.save(config("ollama", "local", "auto", Map.of("think", false, "top_k", 9)));
        handler = request -> new Reply(200, "{\"response\":\"cut\",\"done\":true,\"done_reason\":\"length\"}");
        assertEquals("AI output token limit reached",
                assertThrows(IllegalStateException.class, () -> client.generate("prompt")).getMessage());
        JsonNode body = requests.getFirst().body();
        assertEquals(9, body.at("/options/top_k").intValue());
        assertEquals(1234, body.at("/options/num_predict").intValue());
        assertTrue(body.has("think"));
        assertFalse(body.path("options").has("think"));
        assertFalse(body.path("options").has("stream"));
    }

    @Test
    void autoStreamingRetriesOnceWhenRequiredAndKeepsExplicitFalse() throws Exception {
        handler = request -> request.body().path("stream").asBoolean()
                ? new Reply(200, RESPONSES) : new Reply(400, """
                        {"error":{"code":"stream_required","message":"Stream must be set to true"}}
                        """);
        store.save(config("openai", "model", "responses", Map.of()));
        assertEquals("done", client.generate("test"));
        assertEquals(2, requests.size());
        assertTrue(requests.getLast().body().get("stream").booleanValue());
        client.generate("test");
        assertEquals(3, requests.size());
        store.save(config("openai", "model", "responses", Map.of("stream", false)));
        assertThrows(IllegalStateException.class, () -> client.generate("test"));
        assertEquals(4, requests.size());
    }

    @Test
    void eachAttemptReservesRadarBudgetAndOnlySentRequestsGetMetrics() throws Exception {
        handler = request -> new Reply(404, "missing endpoint");
        AiConfig config = config("openai", "chosen", "auto", Map.of());
        store.save(config);
        var checks = new AtomicInteger();
        var metrics = new CopyOnWriteArrayList<GenerationMetrics>();
        try (var scope = client.withScope(config, () -> {
            if (checks.incrementAndGet() > 1) throw new IllegalStateException("Budget exhausted");
        }, metrics::add)) {
            assertEquals("Budget exhausted",
                    assertThrows(IllegalStateException.class, () -> client.generate("test")).getMessage());
        }
        assertEquals(2, checks.get());
        assertEquals(1, requests.size());
        assertEquals(1, metrics.size());
    }

    @Test
    void disablingAiDuringNegotiationStopsFurtherRequests() {
        store.save(config("openai", "chosen", "auto", Map.of()));
        handler = request -> {
            store.setEnabled(false);
            return new Reply(404, "missing endpoint");
        };
        assertEquals("AI is disabled",
                assertThrows(IllegalStateException.class, () -> client.generate("test")).getMessage());
        assertEquals(1, requests.size());
    }

    @Test
    void recognizesNonTextEvidenceSeparatelyFromEmptyAndBlockedOutput() {
        store.save(config("openai", "model", "chat", Map.of()));
        checkFailure("""
                {"choices":[{"finish_reason":"stop","message":{"content":null,"audio":{"data":"abc"}}}]}
                """, "openai returned an error [audio]");
        checkFailure("""
                {"data":[{"embedding":[0.5,1.2]}]}
                """, "openai returned an error [embedding]");
        checkFailure("""
                {"choices":[{"finish_reason":"stop","message":{"content":null,"reasoning_content":"private"}}]}
                """, "AI platform returned no text");
        store.save(config("gemini", "image-model", "auto", Map.of()));
        checkFailure("""
                {"candidates":[{"finishReason":"STOP","content":{"parts":[{"inlineData":{"mimeType":"image/png"}}]}}]}
                """, "gemini returned an error [image]");
        checkFailure("""
                {"candidates":[{"finishReason":"SAFETY"}]}
                """, "AI response refused");
        checkFailure("""
                {"candidates":[]}
                """, "AI response did not complete");
    }

    private void checkFailure(String response, String error) {
        int count = requests.size();
        handler = request -> new Reply(200, response);
        assertEquals(error, assertThrows(IllegalStateException.class, () -> client.generate("test")).getMessage());
        assertEquals(count + 1, requests.size());
    }

    private AiConfig config(String protocol, String model, String style, Map<String, Object> parameters) {
        return new AiConfig(host, model, true, protocol, "fake-key", 1234, true, "auto", style, parameters);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            var request = new Request(exchange.getRequestURI().toString(),
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes),
                    exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            requests.add(request);
            Reply reply = handler.apply(request);
            byte[] output = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), output.length);
            exchange.getResponseBody().write(output);
        }
    }

    private record Request(String path, JsonNode body, String geminiKey) {}
    private record Reply(int status, String body) {}
}
