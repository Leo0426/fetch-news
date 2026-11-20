package app.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            return selected.withHost(envHost);
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
        return profile(current.activeProfile()).config().withEnabled(current.enabled());
    }

    public synchronized String saveProfile(String id, String name, AiConfig config, boolean activate) {
        return saveProfile(id, name, config, activate, null);
    }

    public synchronized String saveProfile(
            String id, String name, AiConfig config, boolean activate, List<String> models) {
        return saveProfile(id, name, config, activate, models, null);
    }

    public synchronized String saveProfile(String id, String name, AiConfig config, boolean activate,
                                           List<String> models, Map<String, ModelCapabilities> overrides) {
        Provider previous = id == null || id.isBlank() ? null : profile(id);
        if (models == null) {
            models = new ArrayList<>(previous == null ? List.of() : previous.models());
            if (!models.contains(config.model())) models.add(config.model());
        }
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("Platform name must be 1–100 characters");
        }
        var options = new LinkedHashMap<String, ModelCapabilities>();
        if (previous != null && previous.config().protocol().equals(config.protocol())) {
            for (String model : models) {
                if (model != null && previous.modelCapabilities().containsKey(model.strip())) {
                    options.put(model.strip(), previous.modelCapabilities().get(model.strip()));
                }
            }
        }
        if (previous == null || previous.config().model().equals(config.model())
                || !previous.config().capabilities().equals(config.capabilities())) {
            options.put(config.model(), config.capabilities());
        }
        if (overrides != null) options.putAll(overrides);
        String profileId = previous == null ? UUID.randomUUID().toString() : previous.id();
        List<Provider> providers = new ArrayList<>(current.profiles());
        providers.removeIf(p -> p.id().equals(profileId));
        providers.add(new Provider(profileId, name.strip(), config.withEnabled(false), models, options));
        persist(new Settings(
                activate ? profileId : current.activeProfile(),
                activate ? config.enabled() : current.enabled(),
                providers));
        return profileId;
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
        try (var input = Files.newInputStream(filePath)) {
            var node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(input);
            if (node == null || !node.isObject()) throw new IllegalStateException("AI 配置文件必须包含一个 JSON 对象。");
            return node.has("profiles")
                    ? mapper.treeToValue(node, Settings.class)
                    : legacy(mapper.treeToValue(node, AiConfig.class));
        } catch (JsonProcessingException e) {
            if (e.getCause() instanceof ModelCapabilities.InvalidOption invalid)
                throw new IllegalStateException("AI 配置文件无效。" + invalid.getMessage());
            var location = e.getLocation();
            String at = location == null || location.getLineNr() < 1 ? ""
                    : "（第 " + location.getLineNr() + " 行，第 " + location.getColumnNr() + " 列）";
            throw new IllegalStateException("AI 配置文件无效" + at + "，请检查 JSON 格式、提供商和模型配置。");
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 AI 配置文件，请检查文件路径和读取权限。");
        }
    }

    private Settings legacy(AiConfig config) {
        return new Settings(
                "default", config.enabled(), List.of(new Provider("default", "Ollama", config.withEnabled(false))));
    }

    public record Provider(String id, String name, AiConfig config, List<String> models,
                           Map<String, ModelCapabilities> modelCapabilities) {
        public Provider(String id, String name, AiConfig config, List<String> models) {
            this(id, name, config, models, null);
        }

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
            Map<String, ModelCapabilities> saved = modelCapabilities == null ? Map.of() : modelCapabilities;
            if (!models.containsAll(saved.keySet())) throw new IllegalArgumentException("模型选项只能用于此提供商的模型");
            var resolved = new LinkedHashMap<String, ModelCapabilities>();
            ModelCapabilities inherited = config.capabilities();
            for (String model : models) {
                ModelCapabilities options = saved.getOrDefault(model, inherited);
                if (options == null) throw new IllegalArgumentException("模型选项不能为空");
                try {
                    options.validateProtocol(config.protocol());
                } catch (ModelCapabilities.InvalidOption e) {
                    throw new IllegalArgumentException("模型 " + model + "：" + e.getMessage());
                }
                resolved.put(model, options);
            }
            modelCapabilities = Map.copyOf(resolved);
            config = config.withCapabilities(resolved.get(config.model()));
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
