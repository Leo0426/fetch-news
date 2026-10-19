package app.radar;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record Profile(String id, String version, String match, String scoring, Map<String, String> blocks) {
    public Profile {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,63}") || version == null || version.isBlank()
                || match == null || match.isBlank() || scoring == null || scoring.isBlank() || blocks == null || blocks.isEmpty())
            throw new IllegalArgumentException("Invalid Profile definition");
        blocks = Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
    }
}
