package app.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads, saves, and lists persisted route configuration.
 */
public class RouteConfigStore {
    private final Path configFile;
    private final ObjectMapper objectMapper;
    private final Map<String, RouteConfig> configs = new LinkedHashMap<>();

    /**
     * Creates a store backed by a JSON file.
     *
     * @param configFile route configuration file path
     * @param objectMapper JSON mapper used for persistence
     */
    public RouteConfigStore(Path configFile, ObjectMapper objectMapper) {
        this.configFile = configFile;
        this.objectMapper = objectMapper;
        load();
    }

    /**
     * Lists default registered route configs followed by saved mount aliases.
     *
     * @param routePaths registered route paths
     * @return route configs visible to the admin API
     */
    public synchronized List<RouteConfig> list(List<String> routePaths) {
        List<RouteConfig> registered = routePaths.stream().map(this::get).toList();
        List<RouteConfig> aliases = configs.values().stream()
                .filter(config -> !routePaths.contains(config.path()))
                .filter(config -> routePaths.contains(config.sourcePath()))
                .toList();
        return java.util.stream.Stream.concat(registered.stream(), aliases.stream()).toList();
    }

    /**
     * Returns a saved configuration or a default configuration for the path.
     *
     * @param path route path
     * @return effective route configuration
     */
    public synchronized RouteConfig get(String path) {
        return configs.getOrDefault(path, RouteConfig.defaults(path));
    }

    /**
     * Persists a route configuration and updates the in-memory snapshot.
     *
     * @param config configuration to save
     * @return saved configuration
     */
    public synchronized RouteConfig save(RouteConfig config) {
        Map<String, RouteConfig> next = new LinkedHashMap<>(configs);
        next.put(config.path(), config);
        write(next);
        configs.clear();
        configs.putAll(next);
        return config;
    }

    /**
     * Removes a saved route configuration.
     * Registered routes revert to defaults on next access; mount aliases disappear from the list.
     *
     * @param path route path to remove
     */
    public synchronized void remove(String path) {
        Map<String, RouteConfig> next = new LinkedHashMap<>(configs);
        if (next.remove(path) != null) {
            write(next);
            configs.clear();
            configs.putAll(next);
        }
    }

    /**
     * Loads route configuration from disk when the file exists.
     */
    private void load() {
        if (!Files.exists(configFile)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(configFile.toFile());
            JsonNode routes = root.path("routes");
            if (!routes.isObject()) {
                throw new RouteException(RouteError.INVALID_PARAMETER, "route config file must contain a routes object");
            }
            routes.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                RouteConfig defaults = RouteConfig.defaults(entry.getKey());
                JsonNode cronNode = value.path("scheduleCron");
                String scheduleCron = (!cronNode.isMissingNode() && !cronNode.isNull())
                        ? cronNode.asText() : null;
                JsonNode feedUrlNode = value.path("feedUrl");
                String feedUrl = (!feedUrlNode.isMissingNode() && !feedUrlNode.isNull())
                        ? feedUrlNode.asText() : null;
                JsonNode aliasNode = value.path("alias");
                String alias = (!aliasNode.isMissingNode() && !aliasNode.isNull())
                        ? aliasNode.asText() : null;
                configs.put(entry.getKey(), new RouteConfig(
                        entry.getKey(),
                        value.path("sourcePath").asText(defaults.sourcePath()),
                        value.path("enabled").asBoolean(defaults.enabled()),
                        value.path("routeCacheTtlSeconds").asInt(defaults.routeCacheTtlSeconds()),
                        value.path("detailCacheTtlSeconds").asInt(defaults.detailCacheTtlSeconds()),
                        value.path("scheduleMinutes").asInt(0),
                        scheduleCron,
                        feedUrl,
                        alias));
            });
        } catch (IOException e) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "failed to read route config: " + e.getMessage(), e);
        }
    }

    /**
     * Writes the supplied configuration snapshot to disk.
     *
     * @param configsToWrite complete route configuration map to persist
     */
    private void write(Map<String, RouteConfig> configsToWrite) {
        try {
            Path parent = configFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode routes = root.putObject("routes");
            for (RouteConfig config : configsToWrite.values()) {
                ObjectNode node = routes.putObject(config.path());
                node.put("sourcePath", config.sourcePath());
                node.put("enabled", config.enabled());
                node.put("routeCacheTtlSeconds", config.routeCacheTtlSeconds());
                node.put("detailCacheTtlSeconds", config.detailCacheTtlSeconds());
                node.put("scheduleMinutes", config.scheduleMinutes());
                if (config.scheduleCron() != null) {
                    node.put("scheduleCron", config.scheduleCron());
                }
                if (config.feedUrl() != null) {
                    node.put("feedUrl", config.feedUrl());
                }
                if (config.alias() != null) {
                    node.put("alias", config.alias());
                }
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), root);
        } catch (IOException e) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "failed to write route config: " + e.getMessage(), e);
        }
    }
}
