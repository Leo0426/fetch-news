package app.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import app.store.FeedStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArticleAnalyzerTest {
    @TempDir Path directory;
    @Test void validatesScoresAndReusesOnlyMatchingInputAndPromptCache() throws Exception {
        var candidate=RadarFixtures.candidate("https://a");
        var run=RadarFixtures.run(RadarConfig.defaults(),List.of(candidate));
        try (var store = new FeedStore(directory.resolve("feed.db"),RadarFixtures.MAPPER)) {
            var analyzer=new ArticleAnalyzer(RadarFixtures.model(directory,"{\"score\":7,\"reason\":\"Useful\",\"summary\":\"Facts\",\"tags\":[\"AI\"]}"),store,RadarFixtures.MAPPER);
            var first=analyzer.analyze(candidate,run.profiles().get("tech-news"),run);
            assertEquals(7.0,first.score()); assertTrue(first.limitedInput());
            store.saveRadarAnalysis("run",first);
            var invalid=new ArticleAnalyzer(RadarFixtures.model(directory,"{\"score\":11,\"reason\":\"x\",\"summary\":\"x\",\"tags\":[]}"),store,RadarFixtures.MAPPER);
            assertEquals("Facts",invalid.analyze(candidate,run.profiles().get("tech-news"),run).summary());
            assertThrows(Exception.class, () -> invalid.analyze(RadarFixtures.candidate("https://changed"),run.profiles().get("tech-news"),run));
            assertThrows(Exception.class, () -> invalid.analyze(candidate,new Profile("tech-news","2","News","New rubric",java.util.Map.of("summary","x")),run));
        }
    }
    @Test void rejectsMissingFieldsAndNonNumericScore() {
        var candidate=RadarFixtures.candidate("https://a");
        var run=RadarFixtures.run(RadarConfig.defaults(),List.of(candidate));
        for (String json:List.of("{}","{\"score\":\"NaN\"}","{\"score\":-1}","{\"score\":10,\"reason\":\"x\",\"summary\":\"x\",\"tags\":[]} trailing garbage")) {
            var analyzer=new ArticleAnalyzer(RadarFixtures.model(directory,json),null,RadarFixtures.MAPPER);
            assertThrows(Exception.class, () -> analyzer.analyze(candidate,run.profiles().get("tech-news"),run));
        }
    }
}
