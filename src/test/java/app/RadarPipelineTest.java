package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.radar.RadarTestEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadarPipelineTest {
    @TempDir
    Path directory;

    @Test
    void failedRebuildKeepsPublishedVersionAndDisabledAiDoesNotConsumeQueue() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}")) {
            String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
            env.save("https://a.test/1");
            var first = env.service.submit(date, "Asia/Shanghai", "first");
            env.worker.processNext();
            assertEquals("success", env.service.status(first.runId()).state());
            env.save("https://a.test/new");
            env.scoreResponse = "{}";
            var second = env.service.submit(date, "Asia/Shanghai", "second");
            var ai = env.ai.get();
            env.ai.save(new app.ai.AiConfig(ai.host(), ai.model(), false));
            int calls = env.requests.get();
            env.worker.processNext();
            assertEquals(calls, env.requests.get());
            env.ai.save(ai);
            env.worker.processNext();
            assertEquals("partial", env.service.status(second.runId()).state());
            assertEquals(
                    first.runId(),
                    env.store
                            .radar()
                            .latestRun(date, "Asia/Shanghai", true)
                            .status()
                            .runId());
        }
    }

    @Test
    void fixedScenariosExerciseThresholdAndInvalidAnalysisThroughRealHttpAndSqlite() throws Exception {
        var mapper = new ObjectMapper();
        try (var input = getClass().getResourceAsStream("/radar/scenarios.json")) {
            for (var scenario : mapper.readTree(input)) {
                var path = directory.resolve(scenario.path("name").asText());
                Files.createDirectory(path);
                try (var env = new RadarTestEnvironment(path, "{\"enabled\":true}")) {
                    env.save("https://a.test/1");
                    env.scoreResponse = scenario.path("response").toString();
                    var run = env.service.submit(
                            LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "Asia/Shanghai", "scenario");
                    env.worker.processNext();
                    assertEquals(
                            scenario.path("state").asText(),
                            env.service.status(run.runId()).state());
                    assertEquals(
                            scenario.path("stories").asInt(),
                            env.store.radar().loadReport(run.runId()).stories().size());
                    assertEquals(
                            1,
                            env.store
                                    .radar()
                                    .loadReport(run.runId())
                                    .decisions()
                                    .size());
                }
            }
        }
    }
}
