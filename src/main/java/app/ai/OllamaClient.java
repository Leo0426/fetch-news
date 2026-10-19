package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Ollama local REST API.
 * Host and model are read from {@link AiConfigStore} on every call
 * so admin-page changes take effect immediately without a restart.
 */
public class OllamaClient {
    private final AiGenerationGate gate = new AiGenerationGate();
    private final ThreadLocal<GenerationScope> scope = new ThreadLocal<>();
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final AiConfigStore configStore;

    public OllamaClient(AiConfigStore configStore, ObjectMapper mapper) {
        this.configStore = configStore;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Sends a prompt to Ollama and returns the generated text.
     *
     * @param prompt the full prompt string
     * @return generated response text
     * @throws Exception on HTTP or JSON errors
     */
    public String generate(String prompt) throws Exception {
        return generateWithMetadata(prompt).text();
    }

    public Generation generateWithMetadata(String prompt) throws Exception {
        return generateRequest(prompt, null, false);
    }

    public Generation generateJson(String prompt, AiConfig expected) throws Exception {
        return generateRequest(prompt, expected, true);
    }

    public Scope withScope(AiConfig expected, BeforeGeneration observer) {
        if (scope.get() != null) throw new IllegalStateException("Generation scope already installed");
        scope.set(new GenerationScope(expected, observer));
        return () -> scope.remove();
    }

    private Generation generateRequest(String prompt, AiConfig expected, boolean json) throws Exception {
        try (var permit = gate.acquire()) {
            AiConfig config = configStore.get();
            if (!config.enabled()) throw new IllegalStateException("AI is disabled");
            GenerationScope context = scope.get();
            AiConfig locked = context == null ? expected : context.expected();
            if (locked != null && (!locked.host().equals(config.host()) || !locked.model().equals(config.model())))
                throw new IllegalStateException("AI configuration changed; resume with the original model or start a new run");
            if (context != null) context.observer().before();
            Map<String,Object> payload = new java.util.LinkedHashMap<>();
            payload.put("model", config.model()); payload.put("prompt", prompt); payload.put("stream", false);
            if (json) payload.put("format", "json");
            String body = mapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.host() + "/api/generate"))
                    .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Ollama returned HTTP " + response.statusCode());
            JsonNode node = mapper.readTree(response.body());
            return new Generation(node.path("response").asText(), config.model());
        }
    }

    @FunctionalInterface public interface BeforeGeneration { void before() throws Exception; }
    @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }
    private record GenerationScope(AiConfig expected, BeforeGeneration observer) {}

    public record Generation(String text, String model) {}

    /**
     * Probes the Ollama server without triggering inference.
     * Calls {@code /api/version} and {@code /api/tags} — both return quickly
     * regardless of model state.
     *
     * @return connection status with server version, model availability and model list
     */
    public ConnectionStatus checkConnection() {
        AiConfig config = configStore.get();
        String host = config.host();
        String model = config.model();
        try {
            String version = fetchVersion(host);
            List<String> models = fetchModels(host);
            // Ollama model names may include a tag suffix (e.g. "llama3.2:latest").
            // Match by prefix so "llama3.2" matches "llama3.2:latest".
            boolean modelFound = models.stream()
                    .anyMatch(m -> m.equals(model) || m.startsWith(model + ":"));
            return new ConnectionStatus(true, version, modelFound, models, null);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
            return new ConnectionStatus(false, null, false, List.of(), msg);
        }
    }

    private String fetchVersion(String host) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/api/version"))
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return mapper.readTree(resp.body()).path("version").asText("?");
    }

    private List<String> fetchModels(String host) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/api/tags"))
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        List<String> names = new ArrayList<>();
        for (JsonNode m : mapper.readTree(resp.body()).path("models")) {
            String name = m.path("name").asText(null);
            if (name != null) names.add(name);
        }
        return names;
    }

    /** Returns the currently configured model name. */
    public String model() {
        return configStore.get().model();
    }

    /** Returns the currently configured host. */
    public String host() {
        return configStore.get().host();
    }

    /**
     * Result of a connectivity probe — no inference involved.
     *
     * @param serverReachable  whether the Ollama HTTP server responded
     * @param serverVersion    Ollama version string, or {@code null} if unreachable
     * @param modelAvailable   whether the configured model appears in {@code /api/tags}
     * @param availableModels  all model names reported by the server
     * @param error            error message when {@code serverReachable} is false
     */
    public record ConnectionStatus(
            boolean serverReachable,
            String serverVersion,
            boolean modelAvailable,
            List<String> availableModels,
            String error) {}
}
