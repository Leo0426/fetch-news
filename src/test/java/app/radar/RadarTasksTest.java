package app.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.ai.GenerationMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadarTasksTest {
    @TempDir Path directory;
    @Test void parsesProviderUsageAndKeepsMissingCountsUnknown() throws Exception {
        var mapper = new ObjectMapper();
        assertEquals(20L, GenerationMetrics.from("openai", mapper.readTree("{\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5}}"), 10).inputTokens());
        assertEquals(5L, GenerationMetrics.from("anthropic", mapper.readTree("{\"usage\":{\"input_tokens\":20,\"output_tokens\":5}}"), 10).outputTokens());
        assertEquals(7L, GenerationMetrics.from("ollama", mapper.readTree("{\"prompt_eval_count\":7,\"eval_count\":2}"), 10).inputTokens());
        assertEquals(null, GenerationMetrics.from("openai", mapper.readTree("{}"), 10).inputTokens());
    }
    @Test void listsDurableRunsAndRetriesAsANewVersion() throws Exception {
        try (var env = new RadarTestEnvironment(directory,"{\"enabled\":true}")) {
            String day=LocalDate.now().toString();
            var run=env.service.submit(day,"UTC","first");
            assertThrows(IllegalStateException.class,()->env.service.retry(run.runId(),"second"));
            env.store.updateRadarState(run.runId(),"failed","routing","failure");
            var retry=env.service.retry(run.runId(),"second");
            assertEquals(2,retry.version());
            assertEquals(2,env.store.listRadarRuns(50).size());
            env.worker.processNext();
            env.worker.processNext();
            assertNotNull(env.store.loadRadarRun(retry.runId()));
        }
    }
}
