package app.radar;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileStoreTest {
    @TempDir Path directory;
    @Test void installsDefaultsWithoutOverwritingCustomPrompts() throws Exception {
        var store = new ProfileStore(directory, new ObjectMapper());
        assertEquals(java.util.Set.of("tech-news", "tech-blog"), store.loadAll().keySet());
        Files.writeString(directory.resolve("tech-news/scoring.md"), "My custom scoring");
        assertEquals("My custom scoring", new ProfileStore(directory, new ObjectMapper()).loadAll().get("tech-news").scoring());
    }
    @Test void rejectsPromptPathsOutsideProfileDirectory() throws Exception {
        var store = new ProfileStore(directory, new ObjectMapper());
        Files.writeString(directory.resolve("outside.md"), "external");
        Files.writeString(directory.resolve("tech-news/profile.json"), """
                {"id":"tech-news","version":"1","match":"match.md","scoring":"../outside.md","blocks":{"summary":"summary.md"}}
                """);
        assertThrows(Exception.class, store::loadAll);
    }
}
