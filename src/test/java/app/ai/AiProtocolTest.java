package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AiProtocolTest {
    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void openAiUsesConfiguredPrefixBearerAndOptionalJsonMode() throws Exception {
        var received = new AtomicReference<JsonNode>();
        var path = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var output = new AtomicReference<>(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}");
        HttpServer server = server(output, (e, input) -> {
            received.set(input);
            path.set(e.getRequestURI().getPath());
            authorization.set(e.getRequestHeaders().getFirst("Authorization"));
            return 200;
        });
        try {
            var configs = new AiConfigStore(directory.resolve("openai.json"), mapper);
            var config = new AiConfig(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/relay/v1/",
                    "custom-model",
                    true,
                    "openai",
                    "test-secret",
                    2048,
                    true,
                    "max_completion_tokens");
            configs.save(config);
            var client = new OllamaClient(configs, mapper);
            assertEquals(
                    "{\"ok\":true}", client.generateJson("Return JSON", config).text());
            assertEquals("/relay/v1/chat/completions", path.get());
            assertEquals("Bearer test-secret", authorization.get());
            assertEquals("custom-model", received.get().path("model").asText());
            assertEquals(2048, received.get().path("max_completion_tokens").asInt());
            assertEquals(
                    "json_object",
                    received.get().path("response_format").path("type").asText());
            assertFalse(received.get().path("stream").asBoolean());
            assertEquals(
                    "user", received.get().path("messages").get(0).path("role").asText());
            configs.save(new AiConfig(
                    config.host(), config.model(), true, "openai", config.apiKey(), 2048, false, "max_tokens"));
            client.generateJson("Return JSON", configs.get());
            assertFalse(received.get().has("response_format"));
            assertEquals(2048, received.get().path("max_tokens").asInt());
            output.set("{\"choices\":[{\"message\":{\"content\":\"cut\"},\"finish_reason\":\"length\"}]}");
            assertEquals(
                    "AI output token limit reached",
                    assertThrows(Exception.class, () -> client.generate("test")).getMessage());
            output.set("{\"choices\":[{\"message\":{\"refusal\":\"private provider"
                    + " detail\"},\"finish_reason\":\"stop\"}]}");
            assertEquals(
                    "AI response refused",
                    assertThrows(Exception.class, () -> client.generate("test")).getMessage());
            output.set("{\"choices\":[{\"message\":{\"tool_calls\":[{}]},\"finish_reason\":\"tool_calls\"}]}");
            assertEquals(
                    "AI response requires unsupported tools",
                    assertThrows(Exception.class, () -> client.generate("test")).getMessage());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"max_tokens, max_completion_tokens", "max_completion_tokens, max_tokens"})
    void retriesOnlyTheRejectedOutputLimitField(String configured, String alternative) throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var output = new AtomicReference<String>();
        var server = server(output, (exchange, input) -> {
            requests.add(input);
            if (input.has(configured)) {
                output.set(parameterError("unsupported_parameter", configured));
                return 400;
            }
            output.set("""
                    {"choices":[{"message":{"content":"done"},"finish_reason":"stop"}]}
                    """);
            return 200;
        });
        try {
            var client = openAiClient(server, configured);
            assertEquals("done", client.generateJson("Return JSON", null).text());
            assertEquals(2, requests.size());
            ObjectNode expected = requests.getFirst().deepCopy();
            assertEquals(2048, expected.path(configured).asInt());
            assertFalse(expected.has(alternative));
            expected.set(alternative, expected.remove(configured));
            assertEquals(expected, requests.getLast());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "400,unsupported_parameter,response_format,1",
        "400,unsupported_parameter,max_completion_tokens,1",
        "400,unsupported_value,max_tokens,1",
        "401,unsupported_parameter,max_tokens,1",
        "400,unsupported_parameter,sent_field,2"
    })
    void preservesOtherProviderFailuresAndBoundsParameterRetry(int status, String code, String param,
            int expectedRequests) throws Exception {
        var count = new AtomicInteger();
        var output = new AtomicReference<String>();
        var server = server(output, (exchange, input) -> {
            count.incrementAndGet();
            String sent = input.has("max_tokens") ? "max_tokens" : "max_completion_tokens";
            output.set(parameterError(code, param.equals("sent_field") ? sent : param));
            return status;
        });
        try {
            var client = openAiClient(server, "max_tokens");
            String message = assertThrows(IllegalStateException.class, () -> client.generate("test")).getMessage();
            assertTrue(message.startsWith("openai returned HTTP " + status));
            assertFalse(message.contains("private upstream error"));
            assertEquals(expectedRequests, count.get());
        } finally {
            server.stop(0);
        }
    }

    private String parameterError(String code, String param) throws Exception {
        return mapper.writeValueAsString(Map.of("error",
                Map.of("code", code, "param", param, "message", "private upstream error")));
    }

    private OllamaClient openAiClient(HttpServer server, String parameter) {
        var configs = new AiConfigStore(directory.resolve("openai.json"), mapper);
        configs.save(new AiConfig("http://127.0.0.1:" + server.getAddress().getPort(), "custom-model", true,
                "openai", "test-secret", 2048, true, parameter));
        return new OllamaClient(configs, mapper);
    }

    @Test
    void anthropicUsesMessagesHeadersAndOnlyTextBlocks() throws Exception {
        var received = new AtomicReference<JsonNode>();
        var headers = new AtomicReference<Map<String, String>>();
        var output = new AtomicReference<>(
                "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"hidden\"},{\"type\":\"text\",\"text\":\"first\"},{\"type\":\"text\",\"text\":\"second\"}],\"stop_reason\":\"end_turn\"}");
        HttpServer server = server(output, (e, input) -> {
            received.set(input);
            headers.set(Map.of(
                    "key",
                    e.getRequestHeaders().getFirst("x-api-key"),
                    "version",
                    e.getRequestHeaders().getFirst("anthropic-version"),
                    "path",
                    e.getRequestURI().getPath()));
            return 200;
        });
        try {
            var configs = new AiConfigStore(directory.resolve("anthropic.json"), mapper);
            configs.save(new AiConfig(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages",
                    "claude-custom",
                    true,
                    "anthropic",
                    "anthropic-secret",
                    1024,
                    false,
                    "max_tokens"));
            var client = new OllamaClient(configs, mapper);
            assertEquals("first\nsecond", client.generate("question"));
            assertEquals("/v1/messages", headers.get().get("path"));
            assertEquals("anthropic-secret", headers.get().get("key"));
            assertEquals("2023-06-01", headers.get().get("version"));
            assertEquals(1024, received.get().path("max_tokens").asInt());
            assertFalse(received.get().has("response_format"));
            output.set("{\"content\":[{\"type\":\"text\",\"text\":\"cut\"}],\"stop_reason\":\"max_tokens\"}");
            assertThrows(Exception.class, () -> client.generate("test"));
            output.set("{\"error\":{\"message\":\"anthropic-secret\"}}");
            Exception failure = assertThrows(Exception.class, () -> client.generate("test"));
            assertFalse(failure.toString().contains("anthropic-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void protocolSwitchIsDetectedBeforeAnyRequestWhenHostAndModelStayTheSame() {
        var configs = new AiConfigStore(directory.resolve("identity.json"), mapper);
        var previous = new AiConfig("http://127.0.0.1:1", "same", true);
        configs.save(new AiConfig(previous.host(), previous.model(), true, "openai", "key", 4096, true, "max_tokens"));
        var client = new OllamaClient(configs, mapper);
        assertThrows(IllegalStateException.class, () -> client.generateJson("JSON", previous));
    }

    private HttpServer server(AtomicReference<String> output, RequestObserver observer) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            try {
                var node = mapper.readTree(e.getRequestBody());
                int status = observer.respond(e, node);
                byte[] body = output.get().getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(status, body.length);
                try (var out = e.getResponseBody()) {
                    out.write(body);
                }
            } catch (Exception failure) {
                e.sendResponseHeaders(500, -1);
                e.close();
            }
        });
        server.start();
        return server;
    }

    @FunctionalInterface
    interface RequestObserver {
        int respond(HttpExchange exchange, JsonNode input) throws Exception;
    }
}
