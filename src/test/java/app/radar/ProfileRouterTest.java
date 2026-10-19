package app.radar;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileRouterTest {
    @TempDir Path directory;
    @Test void fixedBindingWinsAcrossSourcesAndRejectsOutOfCandidateModelChoice() throws Exception {
        var config = RadarFixtures.MAPPER.readValue("""
                {"sources":{"/a":{"profiles":["auto"]},"/b":{"profiles":["tech-blog"],"category":"Engineering"}}}
                """,RadarConfig.class);
        var candidate = new RadarTypes.Candidate("https://a","Title","Body",null,null,0,List.of("/a","/b"));
        var router = new ProfileRouter(RadarFixtures.model(directory,"{\"profile\":\"outside\"}"),RadarFixtures.MAPPER);
        assertEquals("tech-blog",router.resolve(candidate,RadarFixtures.run(config,List.of(candidate))));
        assertEquals("Engineering",router.category(candidate,RadarFixtures.run(config,List.of(candidate))));
        var auto = RadarFixtures.candidate("https://a");
        assertThrows(Exception.class, () -> router.resolve(auto,RadarFixtures.run(config,List.of(auto))));
    }
}
