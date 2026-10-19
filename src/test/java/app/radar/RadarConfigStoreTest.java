package app.radar;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadarConfigStoreTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void defaultsAreOptInAndInvalidConfigurationDoesNotReplaceLastSavedValue() throws Exception {
        Path file = directory.resolve("radar.json");
        var store = new RadarConfigStore(file, mapper);
        assertFalse(store.get().enabled());
        assertEquals(20, store.get().maxItems());
        store.save(mapper.readValue("{\"enabled\":true,\"maxItems\":3}", RadarConfig.class));
        assertEquals(3, new RadarConfigStore(file, mapper).get().maxItems());
        assertThrows(Exception.class, () -> mapper.readValue("{\"zoneId\":\"wrong\"}", RadarConfig.class));
        assertThrows(Exception.class, () -> mapper.readValue("{\"profileSettings\":{\"tech-news\":{\"threshold\":11}}}", RadarConfig.class));
        assertThrows(Exception.class, () -> mapper.readValue("{\"sources\":{\"/a\":{\"profiles\":[]}}}", RadarConfig.class));
        assertThrows(Exception.class, () -> mapper.readValue("{\"scheduleCron\":\"wrong\"}", RadarConfig.class));
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(Exception.class, () -> store.save(RadarConfig.defaults()));
        assertTrue(store.get().enabled());
    }

    @Test void rejectsOverlappingCategoryGroupsAndUnknownProfiles() throws Exception {
        assertThrows(Exception.class, () -> mapper.readValue("""
                {"categoryGroups":{"a":{"limit":2,"categories":["ai"]},"b":{"limit":3,"categories":["ai"]}}}
                """, RadarConfig.class));
        var config = mapper.readValue("{\"defaultProfile\":\"unknown\"}", RadarConfig.class);
        assertThrows(IllegalArgumentException.class, () -> config.validateProfiles(java.util.Set.of("tech-news")));
    }
}
