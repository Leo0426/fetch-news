package app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists {@link AiConfig} to a JSON file and keeps an in-memory copy.
 */
public class AiConfigStore {
    private static final Logger log = LoggerFactory.getLogger(AiConfigStore.class);

    private final Path filePath;
    private final ObjectMapper mapper;
    private volatile AiConfig current;

    public AiConfigStore(Path filePath, ObjectMapper mapper) {
        this.filePath = filePath;
        this.mapper = mapper;
        this.current = load();
    }

    /**
     * Returns the current AI config.
     * The {@code OLLAMA_HOST} environment variable, when set, overrides the persisted host value
     * so Docker deployments can point to the host machine without editing the JSON file.
     */
    public AiConfig get() {
        String envHost = System.getenv("OLLAMA_HOST");
        if (envHost != null && !envHost.isBlank()) {
            return new AiConfig(envHost, current.model(), current.enabled());
        }
        return current;
    }

    /** Saves and applies a new config. Returns the saved value. */
    public AiConfig save(AiConfig config) {
        try {
            Files.createDirectories(filePath.getParent() == null ? Path.of(".") : filePath.getParent());
            mapper.writeValue(filePath.toFile(), config);
            current = config;
        } catch (IOException e) {
            log.warn("failed to save AI config to {}: {}", filePath, e.getMessage());
        }
        return config;
    }

    private AiConfig load() {
        if (!Files.exists(filePath)) return AiConfig.defaults();
        try {
            return mapper.readValue(filePath.toFile(), AiConfig.class);
        } catch (Exception e) {
            log.warn("failed to load AI config from {}, using defaults: {}", filePath, e.getMessage());
            return AiConfig.defaults();
        }
    }
}
