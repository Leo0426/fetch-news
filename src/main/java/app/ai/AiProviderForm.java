package app.ai;

import java.net.URI;
import java.util.Locale;

public record AiProviderForm(String profileId, String name, String host, String model, String protocol,
                             String apiKey, Boolean clearKey, Integer maxTokens, Boolean jsonMode,
                             String tokenLimitParameter) {
    public AiProviderForm {
        clearKey = Boolean.TRUE.equals(clearKey);
        jsonMode = Boolean.TRUE.equals(jsonMode);
        maxTokens = maxTokens == null ? 4096 : maxTokens;
    }

    public AiConfig resolve(AiConfigStore store) {
        return resolve(store, true);
    }

    public AiConfig resolveForDiscovery(AiConfigStore store) {
        return resolve(store, false);
    }

    private AiConfig resolve(AiConfigStore store, boolean requireModel) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("请填写提供商的 API 地址");
        if (requireModel && (model == null || model.isBlank())) throw new IllegalArgumentException("请填写提供商的模型 ID");
        AiConfig previous = profileId == null || profileId.isBlank() ? null : store.profile(profileId).config();
        AiConfig next = new AiConfig(host, model, store.settings().enabled(),
                protocol == null ? "openai" : protocol, clearKey ? "" : apiKey, maxTokens, jsonMode, tokenLimitParameter);
        if (previous != null && next.apiKey().isBlank() && !clearKey && !previous.apiKey().isBlank()) {
            if (!previous.protocol().equals(next.protocol()) || !origin(previous.host()).equals(origin(next.host())))
                throw new IllegalArgumentException("修改协议或平台地址后，请重新输入密钥或勾选清除密钥");
            return new AiConfig(next.host(), next.model(), next.enabled(), next.protocol(), previous.apiKey(),
                    next.maxTokens(), next.jsonMode(), next.tokenLimitParameter());
        }
        return next;
    }

    private static String origin(String host) {
        URI uri = URI.create(host);
        int port = uri.getPort() < 0 ? (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80) : uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    @Override public String toString() { return "AiProviderForm[apiKey=REDACTED]"; }
}
