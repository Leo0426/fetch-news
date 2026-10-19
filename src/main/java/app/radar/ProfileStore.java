package app.radar;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProfileStore {
    private final Path directory;
    private final ObjectMapper mapper;
    private static final Map<String, java.util.List<String>> DEFAULT_FILES = Map.of(
            "tech-news", java.util.List.of("profile.json", "match.md", "scoring.md", "summary.md", "importance.md"),
            "tech-blog", java.util.List.of("profile.json", "match.md", "scoring.md", "background.md", "approach.md", "insights.md"));

    public ProfileStore(Path directory, ObjectMapper mapper) throws IOException {
        this.directory = directory;
        this.mapper = mapper;
        Files.createDirectories(directory);
        for (var entry : DEFAULT_FILES.entrySet()) {
            Path target = directory.resolve(entry.getKey());
            if (Files.exists(target)) continue;
            Files.createDirectories(target);
            for (String name : entry.getValue()) {
                try (var stream = ProfileStore.class.getResourceAsStream("/radar/profiles/" + entry.getKey() + "/" + name)) {
                    if (stream == null) throw new IOException("Missing default Profile resource: " + name);
                    Files.copy(stream, target.resolve(name));
                }
            }
        }
    }

    public Map<String, Profile> loadAll() throws IOException {
        Map<String, Profile> result = new LinkedHashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isDirectory).sorted().toList()) {
                Definition definition = mapper.readValue(path.resolve("profile.json").toFile(), Definition.class);
                if (!path.getFileName().toString().equals(definition.id())) throw new IOException("Profile directory must match ID");
                Map<String, String> blocks = new LinkedHashMap<>();
                for (var block : definition.blocks().entrySet()) {
                    if (!block.getKey().matches("[a-z][a-z0-9_-]{0,63}")) throw new IOException("Invalid block ID");
                    blocks.put(block.getKey(), readPrompt(path, block.getValue()));
                }
                Profile profile = new Profile(definition.id(), definition.version(), readPrompt(path, definition.match()),
                        readPrompt(path, definition.scoring()), blocks);
                if (result.put(profile.id(), profile) != null) throw new IOException("Duplicate Profile ID");
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private String readPrompt(Path profile, String name) throws IOException {
        Path root = profile.toRealPath();
        Path prompt = root.resolve(name).normalize().toRealPath();
        if (!prompt.startsWith(root)) throw new IOException("Prompt path escapes Profile directory");
        return Files.readString(prompt);
    }

    private record Definition(String id, String version, String match, String scoring, Map<String, String> blocks) {}
}
