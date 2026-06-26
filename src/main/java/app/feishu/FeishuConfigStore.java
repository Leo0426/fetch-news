package app.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists {@link FeishuConfig} to a JSON file and keeps an in-memory copy.
 * JSON is parsed manually to handle the nested category map without requiring
 * Jackson record-constructor annotations.
 */
public class FeishuConfigStore {
    private static final Logger log = LoggerFactory.getLogger(FeishuConfigStore.class);

    private final Path filePath;
    private final ObjectMapper mapper;
    private volatile FeishuConfig current;

    public FeishuConfigStore(Path filePath, ObjectMapper mapper) {
        this.filePath = filePath;
        this.mapper = mapper;
        this.current = load();
    }

    public FeishuConfig get() {
        return current;
    }

    public FeishuConfig save(FeishuConfig config) {
        try {
            Files.createDirectories(filePath.getParent() == null ? Path.of(".") : filePath.getParent());
            mapper.writeValue(filePath.toFile(), config);
            current = config;
        } catch (IOException e) {
            log.warn("failed to save Feishu config to {}: {}", filePath, e.getMessage());
        }
        return config;
    }

    private FeishuConfig load() {
        if (!Files.exists(filePath)) return FeishuConfig.defaults();
        try {
            var node = mapper.readTree(filePath.toFile());
            String webhookUrl      = node.path("webhookUrl").asText("");
            boolean enabled        = node.path("enabled").asBoolean(false);
            int maxItemsPerPush    = node.path("maxItemsPerPush").asInt(10);
            var categories = new LinkedHashMap<String, FeishuCategoryConfig>();
            node.path("categories").fields().forEachRemaining(e -> {
                var c = e.getValue();
                categories.put(e.getKey(), new FeishuCategoryConfig(
                        c.path("enabled").asBoolean(false),
                        c.path("webhookUrl").asText(""),
                        c.path("mode").asText(FeishuCategoryConfig.MODE_IMMEDIATE),
                        c.path("digestCron").asText(""),
                        c.path("maxItemsPerPush").asInt(0)));
            });
            return new FeishuConfig(webhookUrl, enabled, maxItemsPerPush, categories);
        } catch (Exception e) {
            log.warn("failed to load Feishu config from {}: {}", filePath, e.getMessage());
            return FeishuConfig.defaults();
        }
    }
}
