package app.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.ai.OllamaClient;
import app.radar.eval.EvaluationService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadarEvaluationTest {
    @TempDir Path directory;
    @Test void evaluatesFixedSamplesWithoutChangingActivePlatformAndRecoversInterruptedJobs() throws Exception {
        try (var env = new RadarTestEnvironment(directory,"{\"enabled\":true}")) {
            String active=env.ai.settings().activeProfile();
            String platform=env.ai.saveProfile(null,"Evaluation",env.ai.get(),false);
            try (var eval = new EvaluationService(env.store,env.ai,new OllamaClient(env.ai,env.mapper),env.mapper)) {
                var first=eval.submit(platform,"eval-1");
                assertEquals(first.id(),eval.submit(platform,"eval-1").id());
                assertFalse(first.payload().contains("apiKey"));
                eval.processNext();
                var saved=env.store.loadRadarJob(first.id());
                assertEquals("success",saved.state());
                assertTrue(saved.payload().contains("results"));
                assertEquals(5,env.requests.get());
                assertEquals(active,env.ai.settings().activeProfile());
                eval.submit(platform,"eval-2");
                var interrupted=env.store.claimRadarJob("evaluation");
                try (var restarted = new EvaluationService(env.store,env.ai,new OllamaClient(env.ai,env.mapper),env.mapper)) {
                    assertEquals("paused",env.store.loadRadarJob(interrupted.id()).state());
                }
            }
        }
    }
}
