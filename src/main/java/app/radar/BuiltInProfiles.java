package app.radar;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The radar Profiles shipped in the jar under {@code /radar/profiles/<id>/}: a {@code profile.json} manifest naming
 * the prompt files for matching, scoring and each output block.
 */
public final class BuiltInProfiles {
    private static final List<String> IDS = List.of("tech-news");

    private final Map<String, Profile> profiles;

    public BuiltInProfiles(ObjectMapper mapper) {
        Map<String, Profile> loaded = new LinkedHashMap<>();
        for (String id : IDS) loaded.put(id, load(mapper, id));
        profiles = Map.copyOf(loaded);
    }

    public Map<String, Profile> all() {
        return profiles;
    }

    private static Profile load(ObjectMapper mapper, String id) {
        try {
            Manifest manifest;
            try (InputStream in = resource(id, "profile.json")) {
                manifest = mapper.readValue(in, Manifest.class);
            }
            if (!id.equals(manifest.id())) throw new IOException("Profile manifest ID must be " + id);
            Map<String, String> blocks = new LinkedHashMap<>();
            for (var block : manifest.blocks().entrySet()) blocks.put(block.getKey(), prompt(id, block.getValue()));
            return new Profile(
                    id, manifest.version(), prompt(id, manifest.match()), prompt(id, manifest.scoring()), blocks);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load radar Profile " + id, e);
        }
    }

    private static String prompt(String id, String file) throws IOException {
        try (InputStream in = resource(id, file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static InputStream resource(String id, String file) throws IOException {
        InputStream in = BuiltInProfiles.class.getResourceAsStream("/radar/profiles/" + id + "/" + file);
        if (in == null) throw new IOException("Missing radar Profile resource: " + id + "/" + file);
        return in;
    }

    private record Manifest(String id, String version, String match, String scoring, Map<String, String> blocks) {}
}
