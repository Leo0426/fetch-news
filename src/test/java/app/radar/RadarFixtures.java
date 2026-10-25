package app.radar;

import app.ai.AiConfig;
import app.ai.AiConfigStore;
import app.ai.OllamaClient;
import app.radar.RadarTypes.Candidate;
import app.radar.RadarTypes.RunRequest;
import app.radar.RadarTypes.RunSnapshot;
import app.radar.RadarTypes.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class RadarFixtures {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    public static Candidate candidate(String link) {
        return new Candidate(link, "A specific technical event", "Saved article body", null, null, 1, List.of("/a"));
    }

    public static RunSnapshot run(RadarConfig config, List<Candidate> candidates) {
        var profiles = Map.of(
                "tech-news",
                new Profile("tech-news", "1", "News", "Score news", Map.of("summary", "Summarize")),
                "tech-blog",
                new Profile("tech-blog", "1", "Blog", "Score blog", Map.of("insights", "Explain insights")));
        return new RunSnapshot(
                new RunStatus("run", "2026-10-19", 1, "running", "scoring", candidates.size(), 0, 0, null),
                new RunRequest(
                        "2026-10-19",
                        "Asia/Shanghai",
                        "key",
                        new AiConfig("http://localhost:1", "test", true),
                        Map.of("/a", "AI")),
                config,
                profiles,
                candidates,
                System.currentTimeMillis(),
                Map.of());
    }

    public static OllamaClient model(Path directory, String response) {
        return new OllamaClient(new AiConfigStore(directory.resolve("unused.json"), MAPPER), MAPPER) {
            @Override
            public Generation generateJson(String prompt, AiConfig expected) {
                return new Generation(response, expected.model());
            }
        };
    }

    private RadarFixtures() {}
}
