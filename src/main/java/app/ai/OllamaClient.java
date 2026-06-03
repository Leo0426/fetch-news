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
        AiConfig config = configStore.get();
        String body = mapper.writeValueAsString(Map.of(
                "model", config.model(),
                "prompt", prompt,
                "stream", false));
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.host() + "/api/generate"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama returned HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode node = mapper.readTree(response.body());
        return node.path("response").asText();
    }

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
