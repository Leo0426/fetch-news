package app.ai;

import java.util.List;
import java.util.NoSuchElementException;

public record AiSettingsPage(List<Platform> platforms, String activeId, Platform editing, AiConfig active) {
    public static AiSettingsPage from(AiConfigStore store, String selectedId) {
        var settings = store.settings();
        var platforms = settings.profiles().stream()
                .map(p -> new Platform(
                        p.id(),
                        p.name(),
                        p.config().withoutCredentials().withEnabled(settings.enabled()),
                        !p.config().apiKey().isBlank(),
                        p.id().equals(settings.activeProfile()),
                        false,
                        p.models()))
                .toList();
        Platform editing;
        if ("new".equals(selectedId))
            editing = new Platform(
                    "",
                    "",
                    new AiConfig(null, null, settings.enabled(), "openai", "", 4096, false, "max_tokens"),
                    false,
                    false,
                    true,
                    List.of());
        else {
            String selected = selectedId == null ? settings.activeProfile() : selectedId;
            editing = platforms.stream()
                    .filter(p -> p.id().equals(selected))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("AI platform not found"));
        }
        return new AiSettingsPage(
                platforms, settings.activeProfile(), editing, store.get().withoutCredentials());
    }

    public String activeName() {
        return platforms.stream()
                .filter(Platform::active)
                .findFirst()
                .orElseThrow()
                .name();
    }

    public record Platform(
            String id,
            String name,
            AiConfig config,
            boolean hasKey,
            boolean active,
            boolean fresh,
            List<String> models) {
        public String protocolLabel() {
            return switch (config.protocol()) {
                case "openai" -> "OpenAI 兼容";
                case "anthropic" -> "Anthropic 兼容";
                default -> "Ollama";
            };
        }
    }
}
