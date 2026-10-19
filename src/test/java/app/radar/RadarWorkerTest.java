package app.radar;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadarWorkerTest {
    @TempDir Path directory;
    @Test void persistsCompletedReportsAndGetStatusDoesNotGenerate() throws Exception {
        try(var env=new RadarTestEnvironment(directory,"{\"enabled\":true}")) {
            env.save("https://a.test/1");
            var run=env.service.submit(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(),"Asia/Shanghai","run");
            env.worker.processNext();
            assertEquals("success",env.service.status(run.runId()).state());
            assertEquals(1,env.store.loadRadarReport(run.runId()).stories().size());
            int calls=env.requests.get();env.service.status(run.runId());env.worker.processNext();
            assertEquals(calls,env.requests.get());
        }
    }
    @Test void budgetStopsNewRequestsAndPublishesExplicitPartialReport() throws Exception {
        try(var env=new RadarTestEnvironment(directory,"{\"enabled\":true,\"maxModelCalls\":1}")) {
            env.save("https://a.test/1");env.save("https://a.test/2");
            var run=env.service.submit(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(),"Asia/Shanghai","run");
            env.worker.processNext();
            assertEquals("partial",env.service.status(run.runId()).state());
            assertEquals(1,env.requests.get());
            assertNotNull(env.store.loadRadarReport(run.runId()));
            assertTrue(env.store.loadRadarReport(run.runId()).failures()>0);
        }
    }
    @Test void interruptedRunRequiresExplicitResumeAndDoesNotLoseInput() throws Exception {
        try(var env=new RadarTestEnvironment(directory,"{\"enabled\":true}")) {
            env.save("https://a.test/1");
            var run=env.service.submit(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(),"Asia/Shanghai","run");
            env.store.updateRadarState(run.runId(),"running","scoring",null);
            env.store.recoverRadarRuns();
            assertEquals("paused",env.service.status(run.runId()).state());
            env.worker.processNext(); assertEquals(0,env.requests.get());
            env.service.resume(run.runId());env.worker.processNext();
            assertEquals("success",env.service.status(run.runId()).state());
        }
    }
}
