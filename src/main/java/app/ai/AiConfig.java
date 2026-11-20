package app.ai;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record AiConfig(
        String host,
        String model,
        boolean enabled,
        String protocol,
        String apiKey,
        int maxTokens,
        boolean jsonMode,
        String tokenLimitParameter,
        String apiStyle,
        Map<String, Object> parameters) {
    public static final String DEFAULT_HOST = "http://localhost:11434";
    public static final String DEFAULT_MODEL = "llama3.2";

    public AiConfig(String host, String model, boolean enabled) {
        this(host, model, enabled, "ollama", "", 4096, true, "max_tokens");
    }

    public AiConfig(String host, String model, boolean enabled, String protocol, String apiKey, int maxTokens,
                    boolean jsonMode, String tokenLimitParameter) {
        this(host, model, enabled, protocol, apiKey, maxTokens, jsonMode, tokenLimitParameter, "auto", Map.of());
    }

    public AiConfig {
        if (protocol == null || protocol.isBlank()) {
            protocol = "ollama";
            jsonMode = true;
        }
        if (!Set.of("ollama", "openai", "anthropic", "gemini").contains(protocol)) {
            throw new IllegalArgumentException("不支持的 AI 协议，请选择 OpenAI、Anthropic、Gemini 或 Ollama。");
        }
        host = baseUrl(protocol, host);
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        if (apiKey.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("API Key 只能包含可打印 ASCII 字符，请检查是否有空格或换行。");
        }
        // Old configuration files and radar snapshots did not always specify a token field.
        tokenLimitParameter = tokenLimitParameter == null ? "max_tokens" : tokenLimitParameter;
        var options = new ModelCapabilities(maxTokens, jsonMode, tokenLimitParameter, apiStyle, parameters);
        options.validateProtocol(protocol);
        maxTokens = options.maxTokens();
        apiStyle = options.apiStyle();
        parameters = options.parameters();
    }

    private static String baseUrl(String protocol, String host) {
        if (host == null || host.isBlank()) {
            return switch (protocol) {
                case "openai" -> "https://api.openai.com/v1";
                case "anthropic" -> "https://api.anthropic.com/v1";
                case "gemini" -> "https://generativelanguage.googleapis.com/v1beta";
                default -> DEFAULT_HOST;
            };
        }
        String base = host.strip().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("API 基础地址必须是有效的 HTTP 或 HTTPS 地址。");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("API 基础地址必须使用 HTTP 或 HTTPS，请移除其中的凭据、查询参数和片段。");
        }
        if (protocol.equals("ollama")) return base;
        String path = uri.getRawPath();
        String normalized;
        if (protocol.equals("gemini")) {
            normalized = path.replaceFirst("/models(?:/[^/]+:(?:streamGenerateContent|generateContent))?$", "");
        } else {
            normalized = path.replaceFirst("/(?:chat/completions|messages|responses)$", "");
        }
        if (normalized.isEmpty()) normalized = protocol.equals("gemini") ? "/v1beta" : "/v1";
        return base.substring(0, base.length() - path.length()) + normalized;
    }

    public static AiConfig defaults() {
        return new AiConfig(null, null, false);
    }

    public ModelCapabilities capabilities() {
        return new ModelCapabilities(maxTokens, jsonMode, tokenLimitParameter, apiStyle, parameters);
    }

    public AiConfig withCapabilities(ModelCapabilities options) {
        return new AiConfig(host, model, enabled, protocol, apiKey, options.maxTokens(), options.jsonMode(),
                options.tokenLimitParameter(), options.apiStyle(), options.parameters());
    }

    public AiConfig withModel(String value) {
        return new AiConfig(host, value, enabled, protocol, apiKey, maxTokens, jsonMode, tokenLimitParameter,
                apiStyle, parameters);
    }

    public AiConfig withHost(String value) {
        return new AiConfig(value, model, enabled, protocol, apiKey, maxTokens, jsonMode, tokenLimitParameter,
                apiStyle, parameters);
    }

    public AiConfig withEnabled(boolean value) {
        return new AiConfig(host, model, value, protocol, apiKey, maxTokens, jsonMode, tokenLimitParameter,
                apiStyle, parameters);
    }

    public AiConfig withoutCredentials() {
        return new AiConfig(host, model, enabled, protocol, "", maxTokens, jsonMode, tokenLimitParameter,
                apiStyle, parameters);
    }

    public List<Object> identity() {
        // Preserve existing analysis cache keys and resumable run identities for unchanged legacy settings.
        if (apiStyle.equals("auto") && parameters.isEmpty()) {
            return List.of(protocol, host, model, maxTokens, jsonMode, tokenLimitParameter);
        }
        return List.of(protocol, host, model, maxTokens, jsonMode, tokenLimitParameter, apiStyle, parameters);
    }

    public boolean sameModel(AiConfig other) {
        return other != null && identity().equals(other.identity());
    }

    @Override
    public String toString() {
        return "AiConfig[protocol=" + protocol + ", model=" + model + ", enabled=" + enabled + ", apiKey=REDACTED]";
    }
}
