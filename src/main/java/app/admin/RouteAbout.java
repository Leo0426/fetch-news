package app.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * A route's "about" blurb shown in the admin route list and edit modal: an intro line and a two-column table.
 * The blurbs live in {@code route-about.json}, keyed by source route path.
 */
record RouteAbout(String intro, List<String> columns, List<List<String>> rows) {
    static final Map<String, RouteAbout> ABOUTS = load();

    private static Map<String, RouteAbout> load() {
        try (InputStream in = RouteAbout.class.getResourceAsStream("/route-about.json")) {
            return Map.copyOf(new ObjectMapper().readValue(in, new TypeReference<Map<String, RouteAbout>>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read route-about.json", e);
        }
    }
}
