package app.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Map<String, List<String>> renamedCategories;
    private volatile FeishuConfig current;

    public FeishuConfigStore(Path filePath, ObjectMapper mapper) {
        this(filePath, mapper, Map.of());
    }

    /**
     * @param renamedCategories old category name → categories that took over its sources; rules saved
     *                          under an old name are copied to each new one that has no rule yet
     */
    public FeishuConfigStore(Path filePath, ObjectMapper mapper, Map<String, List<String>> renamedCategories) {
        this.filePath = filePath;
        this.mapper = mapper;
        this.renamedCategories = renamedCategories;
        FeishuConfig loaded = load();
        FeishuConfig migrated = migrateRenamedCategories(loaded);
        this.current = migrated == loaded ? loaded : save(migrated);
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

    private FeishuConfig migrateRenamedCategories(FeishuConfig config) {
        var categories = new LinkedHashMap<>(config.categories());
        boolean changed = false;
        for (var renamed : renamedCategories.entrySet()) {
            FeishuCategoryConfig rule = categories.remove(renamed.getKey());
            if (rule == null) continue;
            changed = true;
            for (String target : renamed.getValue()) categories.putIfAbsent(target, rule);
            log.info("Feishu rule for category {} moved to {}", renamed.getKey(), renamed.getValue());
        }
        return changed ? new FeishuConfig(config.webhookUrl(), config.enabled(), config.maxItemsPerPush(), categories)
                : config;
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
