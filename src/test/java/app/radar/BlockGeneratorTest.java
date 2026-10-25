package app.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockGeneratorTest {
    @TempDir
    Path directory;

    @Test
    void onlyAcceptsConfiguredTextBlocks() throws Exception {
        var event = new RadarTypes.Event("a", EventDeduplicatorTest.analysis("https://a", "tech-news", 8), List.of());
        var run = RadarFixtures.run(RadarConfig.BUILT_IN, List.of(RadarFixtures.candidate("https://a")));
        var generator = new BlockGenerator(
                RadarFixtures.model(directory, "{\"blocks\":{\"summary\":[\"正文\"]}}"), null, RadarFixtures.MAPPER);
        assertEquals(
                List.of("正文"),
                generator.generate(event, run.profiles().get("tech-news"), run).get("summary"));
        var invalid = new BlockGenerator(
                RadarFixtures.model(directory, "{\"blocks\":{\"summary\":[{\"html\":\"bad\"}]}}"),
                null,
                RadarFixtures.MAPPER);
        assertThrows(
                Exception.class, () -> invalid.generate(event, run.profiles().get("tech-news"), run));
    }
}
