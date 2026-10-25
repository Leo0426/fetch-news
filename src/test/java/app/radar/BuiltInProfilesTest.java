package app.radar;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltInProfilesTest {
    @Test
    void loadsTheShippedProfileWithEveryPrompt() {
        Profile news = new BuiltInProfiles(new ObjectMapper()).all().get("tech-news");
        assertEquals(List.of("summary", "importance"), List.copyOf(news.blocks().keySet()));
        assertFalse(news.match().isBlank());
        assertFalse(news.scoring().isBlank());
        news.blocks().values().forEach(prompt -> assertFalse(prompt.isBlank()));
    }

    @Test
    void builtInConfigurationOnlyNamesShippedProfiles() {
        var profiles = new BuiltInProfiles(new ObjectMapper()).all().keySet();
        assertDoesNotThrow(() -> RadarConfig.BUILT_IN.validateProfiles(profiles));
    }
}
