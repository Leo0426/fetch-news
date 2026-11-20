package app.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Shared generation client for Ollama and compatible hosted AI protocols.
 * Host and model are read from {@link AiConfigStore} on every call
 * so admin-page changes take effect immediately without a restart.
 */
public class OllamaClient {
    private final AiGenerationGate gate = new AiGenerationGate();
    private final ThreadLocal<GenerationScope> scope = new ThreadLocal<>();
    private final ThreadLocal<AiGenerationGate.Priority> priority =
            ThreadLocal.withInitial(() -> AiGenerationGate.Priority.BACKGROUND);
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final AiGenerationTransport transport;
    private final AiConfigStore configStore;

    public OllamaClient(AiConfigStore configStore, ObjectMapper mapper) {
        this.configStore = configStore;
        this.mapper = mapper;
        this.http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.transport = new AiGenerationTransport(http, mapper);
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
        return generateRequest(prompt, null, false, null);
    }

    public Generation generateJson(String prompt, AiConfig expected) throws Exception {
        return generateRequest(prompt, expected, true, null);
    }

    public Generation generatePinnedJson(String prompt, AiConfig config) throws Exception {
        return generateRequest(prompt, null, true, config);
    }

    public Scope withScope(AiConfig expected, BeforeGeneration observer) {
        return withScope(expected, observer, metrics -> {});
    }

    public Scope withScope(AiConfig expected, BeforeGeneration observer, AfterGeneration after) {
        if (scope.get() != null) throw new IllegalStateException("Generation scope already installed");
        scope.set(new GenerationScope(expected, observer, after));
        return () -> scope.remove();
    }

    private Generation generateRequest(String prompt, AiConfig expected, boolean json, AiConfig pinned)
            throws Exception {
        try (var permit = gate.acquire(priority.get())) {
            AiConfig config = pinned == null ? configStore.get() : pinned;
            GenerationScope context = scope.get();
            AiConfig locked = context == null ? expected : context.expected();
            BeforeGeneration before = () -> {
                AiConfig active = configStore.get();
                if (!active.enabled() || !config.enabled()) throw new IllegalStateException("AI is disabled");
                if ((locked != null && !locked.sameModel(config)) || (pinned == null && !active.equals(config)))
                    throw new IllegalStateException(
                            "AI configuration changed; resume with the original model or start a new run");
                if (context != null) context.observer().before();
            };
            return transport.generate(config, prompt, json, before, context == null ? null : context.after());
        }
    }

    public void verifyGeneration(AiConfig config) throws Exception {
        try (var permit = gate.acquire(AiGenerationGate.Priority.INTERACTIVE)) {
            String text = transport.generate(config,
                    "Return exactly this JSON object: {\"ok\":true}", true, null, null).text();
            JsonNode answer;
            try {
                answer = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(text);
            } catch (IOException e) {
                throw new IllegalStateException("AI platform returned unexpected verification content");
            }
            if (answer == null || !answer.isObject() || answer.size() != 1 || !answer.path("ok").isBoolean()
                    || !answer.get("ok").booleanValue())
                throw new IllegalStateException("AI platform returned unexpected verification content");
        }
    }

    @FunctionalInterface
    public interface BeforeGeneration {
        void before() throws Exception;
    }
    /**
     * Until the returned scope closes, generations on this thread — including article extraction —
     * wait ahead of background work such as triage or radar. Generations default to background.
     */
    public Scope interactive() {
        priority.set(AiGenerationGate.Priority.INTERACTIVE);
        return priority::remove;
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    @FunctionalInterface
    public interface AfterGeneration {
        void after(GenerationMetrics metrics) throws Exception;
    }

    private record GenerationScope(AiConfig expected, BeforeGeneration observer, AfterGeneration after) {}

    public record Generation(String text, String model, GenerationMetrics metrics) {
        public Generation(String text, String model) {
            this(text, model, new GenerationMetrics(null, null, 0));
        }
    }

    public ConnectionStatus checkConnection() {
        return checkConnection(configStore.get());
    }

    public ConnectionStatus checkConnection(AiConfig config) {
        if (!config.protocol().equals("ollama")) return checkRemoteConnection(config);
        String model = config.model();
        try {
            String version = fetchVersion(config);
            List<String> models = fetchModels(config);
            // Ollama model names may include a tag suffix (e.g. "llama3.2:latest").
            // Match by prefix so "llama3.2" matches "llama3.2:latest".
            boolean modelFound = models.stream().anyMatch(m -> m.equals(model) || m.startsWith(model + ":"));
            return new ConnectionStatus(true, version, modelFound, models, null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ConnectionStatus(false, null, false, List.of(), "连接失败，请检查 Ollama 地址和网络");
        }
    }

    private ConnectionStatus checkRemoteConnection(AiConfig config) {
        try {
            var request = AiHttp.builder(config, "models")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 || response.statusCode() == 405)
                return new ConnectionStatus(
                        true, config.protocol(), false, List.of(), "平台未提供模型列表；请手动填写模型 ID，实际生成时再验证", false);
            if (response.statusCode() != 200)
                return new ConnectionStatus(
                        false,
                        config.protocol(),
                        false,
                        List.of(),
                        "HTTP " + response.statusCode() + "；请检查 API 地址、密钥及权限",
                        false);
            JsonNode node = mapper.readTree(response.body());
            if (config.protocol().equals("gemini")) return geminiModels(config, node);
            if (!node.path("data").isArray())
                return new ConnectionStatus(true, config.protocol(), false, List.of(), "平台未返回标准模型列表", false);
            List<String> models = new ArrayList<>();
            for (var item : node.path("data"))
                if (item.path("id").isTextual()) models.add(item.path("id").asText());
            return new ConnectionStatus(
                    true, config.protocol(), models.contains(config.model()), List.copyOf(models), null, true);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ConnectionStatus(false, config.protocol(), false, List.of(), "连接失败，请检查平台地址和网络", false);
        }
    }

    private ConnectionStatus geminiModels(AiConfig config, JsonNode page) throws Exception {
        var models = new ArrayList<String>();
        var seen = new HashSet<String>();
        while (true) {
            if (!page.path("models").isArray())
                return new ConnectionStatus(true, "gemini", false, List.of(), "平台未返回标准模型列表", false);
            for (var item : page.path("models")) {
                JsonNode methods = item.path("supportedGenerationMethods");
                boolean textGeneration = methods.isMissingNode();
                for (var method : methods) {
                    if (method.asText().equals("generateContent")) textGeneration = true;
                }
                if (textGeneration && item.path("name").isTextual())
                    models.add(GeminiProtocol.modelId(item.get("name").textValue()));
            }
            String next = page.path("nextPageToken").asText("");
            if (next.isEmpty()) break;
            if (!seen.add(next) || seen.size() > 100) throw new IOException("Invalid model list pagination");
            String operation = "models?pageToken=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
            var request = AiHttp.builder(config, operation).timeout(Duration.ofSeconds(5)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new IOException("Model list returned HTTP " + response.statusCode());
            page = mapper.readTree(response.body());
        }
        return new ConnectionStatus(true, "gemini", models.contains(GeminiProtocol.modelId(config.model())),
                List.copyOf(models), null, true);
    }

    private String fetchVersion(AiConfig config) throws Exception {
        HttpRequest req = AiHttp.builder(config, "api/version")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return mapper.readTree(resp.body()).path("version").asText("?");
    }

    private List<String> fetchModels(AiConfig config) throws Exception {
        HttpRequest req = AiHttp.builder(config, "api/tags")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
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
            String error,
            boolean modelListSupported) {
        public ConnectionStatus(
                boolean serverReachable,
                String serverVersion,
                boolean modelAvailable,
                List<String> availableModels,
                String error) {
            this(serverReachable, serverVersion, modelAvailable, availableModels, error, true);
        }
    }
}
