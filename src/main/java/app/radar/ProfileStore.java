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

    public synchronized Map<String, Profile> loadAll() throws IOException {
        Map<String, Profile> result = new LinkedHashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith(".")).sorted().toList()) {
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

    public synchronized void save(Profile profile) throws IOException {
        for (var block : profile.blocks().entrySet()) {
            if (!block.getKey().matches("[a-z][a-z0-9_-]{0,63}") || block.getValue() == null || block.getValue().isBlank())
                throw new IllegalArgumentException("Invalid block ID or empty prompt");
        }
        Path target = directory.resolve(profile.id());
        if (Files.isSymbolicLink(target)) throw new IOException("Profile directory cannot be a symbolic link");
        boolean creating = !Files.exists(target);
        Path root = creating ? Files.createTempDirectory(directory, ".profile-") : target;
        Path revision = Files.createTempDirectory(root, ".revision-");
        Map<String, String> blocks = new LinkedHashMap<>();
        Files.writeString(revision.resolve("match.md"), profile.match());
        Files.writeString(revision.resolve("scoring.md"), profile.scoring());
        String prefix = revision.getFileName() + "/";
        for (var block : profile.blocks().entrySet()) {
            Files.writeString(revision.resolve("block-" + block.getKey() + ".md"), block.getValue());
            blocks.put(block.getKey(), prefix + "block-" + block.getKey() + ".md");
        }
        Path manifest = Files.createTempFile(root, ".manifest-", ".json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(manifest.toFile(), new Definition(profile.id(), profile.version(),
                    prefix + "match.md", prefix + "scoring.md", blocks));
            Files.move(manifest, root.resolve("profile.json"), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (creating) Files.move(root, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(manifest);
        }
    }

    private String readPrompt(Path profile, String name) throws IOException {
        Path root = profile.toRealPath();
        Path prompt = root.resolve(name).normalize().toRealPath();
        if (!prompt.startsWith(root)) throw new IOException("Prompt path escapes Profile directory");
        return Files.readString(prompt);
    }

    private record Definition(String id, String version, String match, String scoring, Map<String, String> blocks) {}
}
