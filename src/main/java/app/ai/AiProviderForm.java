package app.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record AiProviderForm(String profileId, String name, String host, String model, String protocol,
                             String apiKey, Boolean clearKey, Integer maxTokens, Boolean jsonMode,
                             String tokenLimitParameter, String modelCapabilities) {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public Map<String, ModelCapabilities> overrides(List<String> declaredModels) {
        if (modelCapabilities == null) return null;
        JsonNode root;
        try {
            root = JSON.readTree(modelCapabilities);
        } catch (JsonProcessingException e) {
            var location = e.getLocation();
            String at = location == null ? "" : "（第 " + location.getLineNr() + " 行，第 " + location.getColumnNr() + " 列）";
            throw new IllegalArgumentException("模型配置的 JSON 语法无效" + at + "。");
        }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("模型配置必须是按模型 ID 分组的 JSON 对象。");
        var parsed = new LinkedHashMap<String, ModelCapabilities>();
        for (var entry : root.properties()) {
            String id = entry.getKey();
            // An arbitrary JSON key may contain pasted credentials. Only repeat IDs named by the form.
            String label = id.equals(model) || declaredModels != null && declaredModels.contains(id)
                    ? "模型 " + id : "模型配置";
            parsed.put(id, parseCapabilities(label, entry.getValue()));
        }
        return parsed;
    }

    private static ModelCapabilities parseCapabilities(String label, JsonNode node) {
        if (!node.isObject()) throw new IllegalArgumentException(label + " 的设置必须是 JSON 对象。");
        try {
            return JSON.treeToValue(node, ModelCapabilities.class);
        } catch (JsonProcessingException e) {
            String reason = "模型配置含有未知字段或类型错误。";
            if (e.getCause() instanceof ModelCapabilities.InvalidOption invalid) reason = invalid.getMessage();
            else if (e instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
                String field = mapping.getPath().getFirst().getFieldName();
                reason = switch (field == null ? "" : field) {
                    case "maxTokens" -> "maxTokens 必须是 1 到 1000000 之间的整数。";
                    case "jsonMode" -> "jsonMode 必须是 true 或 false。";
                    case "parameters" -> "附加生成参数 parameters 必须是 JSON 对象。";
                    case "tokenLimitParameter", "apiStyle" -> field + " 必须是支持的选项名称。";
                    default -> "模型配置包含不支持的字段。";
                };
            }
            throw new IllegalArgumentException(label + "：" + reason);
        }
    }

    public AiConfig resolve(AiConfigStore store) {
        return resolve(store, model, true, overrides(null));
    }

    public AiConfig resolveForDiscovery(AiConfigStore store) {
        return resolve(store, null, false, null);
    }

    public AiConfig resolve(AiConfigStore store, Map<String, ModelCapabilities> overrides) {
        return resolve(store, model, true, overrides);
    }

    public AiConfig resolveForTest(AiConfigStore store, String editModel) {
        String testModel = editModel == null || editModel.isBlank() ? model : editModel;
        return resolve(store, testModel, true, overrides(testModel == null ? List.of() : List.of(testModel)));
    }

    private AiConfig resolve(AiConfigStore store, String selectedModel, boolean requireModel,
                             Map<String, ModelCapabilities> overrides) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("请填写提供商的 API 地址");
        if (requireModel && (selectedModel == null || selectedModel.isBlank()))
            throw new IllegalArgumentException("请填写提供商的模型 ID");
        var saved = profileId == null || profileId.isBlank() ? null : store.profile(profileId);
        AiConfig previous = saved == null ? null : saved.config();
        String selectedProtocol = protocol == null ? "openai" : protocol;
        boolean clear = Boolean.TRUE.equals(clearKey);
        ModelCapabilities options = modelOptions(saved, selectedProtocol, selectedModel, overrides);
        AiConfig next = new AiConfig(host, selectedModel, store.settings().enabled(), selectedProtocol,
                clear ? "" : apiKey, options.maxTokens(), options.jsonMode(), options.tokenLimitParameter(),
                options.apiStyle(), options.parameters());
        if (previous != null && next.apiKey().isBlank() && !clear && !previous.apiKey().isBlank()) {
            if (!previous.protocol().equals(next.protocol()) || !origin(previous.host()).equals(origin(next.host())))
                throw new IllegalArgumentException("修改协议或平台地址后，请重新输入密钥或勾选清除密钥");
            return new AiConfig(next.host(), next.model(), next.enabled(), next.protocol(), previous.apiKey(),
                    options.maxTokens(), options.jsonMode(), options.tokenLimitParameter(),
                    options.apiStyle(), options.parameters());
        }
        return next;
    }

    private ModelCapabilities modelOptions(AiConfigStore.Provider saved, String selectedProtocol,
                                           String selectedModel, Map<String, ModelCapabilities> overrides) {
        ModelCapabilities options = ModelCapabilities.defaults(selectedProtocol);
        if (selectedModel == null) return options;
        String id = selectedModel.strip();
        if (overrides != null) {
            if (!overrides.containsKey(id)) throw new IllegalArgumentException("缺少所选模型的能力配置。");
            return overrides.get(id);
        }
        if (saved != null && saved.config().protocol().equals(selectedProtocol))
            options = saved.modelCapabilities().getOrDefault(id, options);
        if (maxTokens == null && jsonMode == null && tokenLimitParameter == null) return options;
        return new ModelCapabilities(maxTokens == null ? options.maxTokens() : maxTokens, Boolean.TRUE.equals(jsonMode),
                tokenLimitParameter == null ? options.tokenLimitParameter() : tokenLimitParameter,
                options.apiStyle(), options.parameters());
    }

    private static String origin(String host) {
        URI uri = URI.create(host);
        int port = uri.getPort() < 0 ? (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80) : uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    @Override public String toString() { return "AiProviderForm[apiKey=REDACTED]"; }
}
