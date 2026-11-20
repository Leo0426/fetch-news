package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Only local reason codes and names of known generation controls may leave this boundary. */
public final class AiProviderFailure extends IllegalStateException {
    private static final Set<String> WIRE_FIELDS = Set.of("max_tokens", "max_completion_tokens", "max_output_tokens",
            "maxOutputTokens", "num_predict", "format", "response_format", "text.format", "responseMimeType", "stream");
    private static final Set<String> MODEL_ERRORS = Set.of("model_not_found", "model_not_available", "unknown_model",
            "model_not_supported", "unsupported_model", "deploymentnotfound");

    private final String model;
    private final boolean configurationFailure;

    private AiProviderFailure(AiConfig config, String detail, boolean configurationFailure) {
        super(detail);
        this.model = config.model();
        this.configurationFailure = configurationFailure;
    }

    String model() {
        return model;
    }

    boolean hasSpecificReason() {
        return configurationFailure || getMessage().endsWith("]");
    }

    static AiProviderFailure wrongEndpoint(AiConfig config, int status) {
        return new AiProviderFailure(config, config.protocol() + " returned HTTP " + status + " [endpoint]", true);
    }

    public boolean affectsEveryArticle() {
        return configurationFailure;
    }

    static AiProviderFailure from(AiConfig config, int status, String body, ObjectMapper mapper) {
        String detail = config.protocol() + " returned HTTP " + status;
        boolean accessDenied = status == 401 || status == 402 || status == 403;
        if (status >= 500 || body == null) return new AiProviderFailure(config, detail, accessDenied);
        JsonNode error;
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null) return new AiProviderFailure(config, detail, accessDenied);
            error = node.hasNonNull("error") ? node.get("error") : node;
        } catch (IOException e) {
            error = mapper.getNodeFactory().textNode(body);
        }
        return classified(config, status, error, detail, accessDenied);
    }

    static AiProviderFailure nonText(AiConfig config, NonTextOutput kind) {
        return new AiProviderFailure(config, config.protocol() + " returned an error [" + kind.reason() + "]", true);
    }

    static AiProviderFailure fromEnvelope(AiConfig config, JsonNode error) {
        return classified(config, 200, error, config.protocol() + " returned an error", false);
    }

    private static AiProviderFailure classified(AiConfig config, int status, JsonNode error, String detail,
                                                boolean accessDenied) {
        String reason = reason(config, status, error);
        boolean configurationFailure = accessDenied
                || Set.of("text", "embedding", "image", "audio", "video", "ranking",
                        "model", "quota", "auth", "permission", "endpoint").contains(reason)
                || reason.startsWith("parameter ");
        String classified = reason.isEmpty() ? detail : detail + " [" + reason + "]";
        return new AiProviderFailure(config, classified, configurationFailure);
    }

    private static String reason(AiConfig config, int status, JsonNode error) {
        String code = error.path("code").asText("").toLowerCase(Locale.ROOT);
        String raw = error.isTextual() ? error.textValue()
                : error.path("message").asText(error.path("detail").asText(""));
        String message = raw.toLowerCase(Locale.ROOT);
        String type = error.path("type").asText("");
        String category = error.path("status").asText("");
        if (type.equals("overloaded_error") || category.equals("UNAVAILABLE")) return "overloaded";
        if (type.equals("authentication_error") || code.equals("invalid_api_key")) return "auth";
        if (type.equals("permission_error") || category.equals("PERMISSION_DENIED")) return "permission";
        if (code.equals("insufficient_quota") || code.equals("billing_hard_limit_reached")) return "quota";
        if (status == 429 || type.equals("rate_limit_error")
                || code.equals("rate_limit_exceeded")
                || category.equals("RESOURCE_EXHAUSTED")) return "rate";
        if (status == 401 || status == 403) return "";
        if (code.equals("context_length_exceeded") || message.contains("maximum context length")
                || message.contains("exceeds the context window")) return "context";
        NonTextOutput kind = NonTextOutput.rejection(message);
        if (kind != null) return kind.reason();
        if (message.contains("not a chat model") || message.contains("does not support chat")
                || message.contains("does not support generate")
                || message.contains("not supported for generatecontent")
                || message.contains("does not support text") || message.contains("only supports embeddings")
                || code.equals("unsupported_modality")) return "text";
        if (Set.of("unsupported_endpoint", "endpoint_not_supported", "unknown_endpoint").contains(code)
                || ((message.contains("chat/completions") || message.contains("/responses"))
                    && (message.contains("not supported") || message.contains("only supported")))) return "endpoint";
        if (MODEL_ERRORS.contains(code) || (status == 404 && message.contains("model")
                && (message.contains("not found") || message.contains("does not exist")))) return "model";
        if (status != 400 && status != 422 && status != 200) return "";
        String param = error.path("param").asText("");
        Set<String> names = new LinkedHashSet<>(WIRE_FIELDS);
        addParameterNames(config.parameters(), "", names);
        if (!param.isEmpty()) {
            for (String name : names) {
                if (param.equals(name) || param.equals("generationConfig." + name) || param.equals("options." + name))
                    return "parameter " + name;
            }
        }
        for (String name : names) {
            String field = name.toLowerCase(Locale.ROOT);
            if (message.startsWith(field + ":") || message.contains("'" + field + "'")
                    || message.contains("\"" + field + "\"")) return "parameter " + name;
        }
        return "";
    }

    private static void addParameterNames(Map<?, ?> values, String prefix, Set<String> names) {
        for (var entry : values.entrySet()) {
            String path = prefix + entry.getKey();
            names.add(path);
            if (entry.getValue() instanceof Map<?, ?> nested) addParameterNames(nested, path + ".", names);
        }
    }
}
