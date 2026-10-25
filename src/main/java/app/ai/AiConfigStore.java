package app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

public class AiConfigStore {
    private final Path filePath;
    private final ObjectMapper mapper;
    private volatile Settings current;

    public AiConfigStore(Path filePath, ObjectMapper mapper) {
        this.filePath = filePath.toAbsolutePath();
        this.mapper = mapper;
        this.current = load();
    }

    public AiConfig get() {
        Settings settings = current;
        AiConfig selected = settings.profiles().stream()
                .filter(p -> p.id().equals(settings.activeProfile()))
                .findFirst()
                .orElseThrow()
                .config()
                .withEnabled(settings.enabled());
        String envHost = System.getenv("OLLAMA_HOST");
        if (selected.protocol().equals("ollama") && envHost != null && !envHost.isBlank())
            return new AiConfig(
                    envHost,
                    selected.model(),
                    selected.enabled(),
                    selected.protocol(),
                    selected.apiKey(),
                    selected.maxTokens(),
                    selected.jsonMode(),
                    selected.tokenLimitParameter());
        return selected;
    }

    public Settings settings() {
        return current;
    }

    public Provider profile(String id) {
        return current.profiles().stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("AI platform not found"));
    }

    public synchronized AiConfig save(AiConfig config) {
        saveProfile(current.activeProfile(), profile(current.activeProfile()).name(), config, true);
        return config;
    }

    public synchronized String saveProfile(String id, String name, AiConfig config, boolean activate) {
        return saveProfile(id, name, config, activate, null);
    }

    public synchronized String saveProfile(
            String id, String name, AiConfig config, boolean activate, List<String> models) {
        if (models == null) {
            models = new ArrayList<>(
                    id == null || id.isBlank() ? List.of() : profile(id).models());
            if (!models.contains(config.model())) models.add(config.model());
        }
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        else profile(id);
        if (name == null || name.isBlank() || name.length() > 100)
            throw new IllegalArgumentException("Platform name must be 1–100 characters");
        String profileId = id;
        List<Provider> providers = new ArrayList<>(current.profiles());
        providers.removeIf(p -> p.id().equals(profileId));
        providers.add(new Provider(id, name.strip(), config.withEnabled(false), models));
        persist(new Settings(
                activate ? id : current.activeProfile(), activate ? config.enabled() : current.enabled(), providers));
        return id;
    }

    public synchronized void activate(String id) {
        profile(id);
        persist(new Settings(id, current.enabled(), current.profiles()));
    }

    public synchronized void setEnabled(boolean enabled) {
        persist(new Settings(current.activeProfile(), enabled, current.profiles()));
    }

    public synchronized void deleteProfile(String id) {
        profile(id);
        if (id.equals(current.activeProfile()))
            throw new IllegalArgumentException("Switch to another platform before deleting the active platform");
        persist(new Settings(
                current.activeProfile(),
                current.enabled(),
                current.profiles().stream().filter(p -> !p.id().equals(id)).toList()));
    }

    private void persist(Settings settings) {
        Path temporary = null;
        try {
            Files.createDirectories(filePath.getParent());
            temporary = Files.createTempFile(filePath.getParent(), ".ai-config-", ".json");
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), settings);
            Files.move(temporary, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            current = settings;
        } catch (IOException e) {
            throw new UncheckedIOException("AI configuration could not be saved", e);
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
        }
    }

    private Settings load() {
        if (!Files.exists(filePath)) return legacy(AiConfig.defaults());
        try {
            var node = mapper.readTree(filePath.toFile());
            return node.has("profiles")
                    ? mapper.treeToValue(node, Settings.class)
                    : legacy(mapper.treeToValue(node, AiConfig.class));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid AI configuration; correct the configuration file");
        }
    }

    private Settings legacy(AiConfig config) {
        return new Settings(
                "default", config.enabled(), List.of(new Provider("default", "Ollama", config.withEnabled(false))));
    }

    public record Provider(String id, String name, AiConfig config, List<String> models) {
        public Provider(String id, String name, AiConfig config) {
            this(id, name, config, null);
        }

        public Provider {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}") || name == null || name.isBlank() || config == null)
                throw new IllegalArgumentException("Invalid AI platform");
            if (models == null) models = List.of(config.model());
            if (models.isEmpty() || models.stream().anyMatch(model -> model == null || model.isBlank()))
                throw new IllegalArgumentException("请至少添加一个模型，模型 ID 不能为空");
            models = models.stream().map(String::strip).distinct().toList();
            if (!models.contains(config.model())) throw new IllegalArgumentException("请从模型列表中选择默认模型");
        }

        @Override
        public String toString() {
            return "Provider[id=" + id + ", name=" + name + ", config=" + config + "]";
        }
    }

    public record Settings(String activeProfile, boolean enabled, List<Provider> profiles) {
        public Settings {
            profiles = List.copyOf(profiles);
            if (profiles.isEmpty()
                    || profiles.stream().map(Provider::id).distinct().count() != profiles.size()
                    || profiles.stream().noneMatch(p -> p.id().equals(activeProfile)))
                throw new IllegalArgumentException("Invalid active AI platform");
        }
    }
}
