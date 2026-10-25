package app.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadarWorkerTest {
    @TempDir
    Path directory;

    @Test
    void persistsCompletedReportsAndGetStatusDoesNotGenerate() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}")) {
            env.save("https://a.test/1");
            var run =
                    env.service.submit(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "Asia/Shanghai", "run");
            env.worker.processNext();
            assertEquals("success", env.service.status(run.runId()).state());
            assertEquals(1, env.store.radar().loadReport(run.runId()).stories().size());
            int calls = env.requests.get();
            env.service.status(run.runId());
            env.worker.processNext();
            assertEquals(calls, env.requests.get());
        }
    }

    @Test
    void budgetStopsNewRequestsAndPublishesExplicitPartialReport() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true,\"maxModelCalls\":1}")) {
            env.save("https://a.test/1");
            env.save("https://a.test/2");
            var run =
                    env.service.submit(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "Asia/Shanghai", "run");
            env.worker.processNext();
            assertEquals("partial", env.service.status(run.runId()).state());
            assertEquals(1, env.requests.get());
            assertNotNull(env.store.radar().loadReport(run.runId()));
            assertTrue(env.store.radar().loadReport(run.runId()).failures() > 0);
        }
    }

    @Test
    void interruptedRunRequiresExplicitResumeAndDoesNotLoseInput() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}")) {
            env.save("https://a.test/1");
            var run =
                    env.service.submit(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "Asia/Shanghai", "run");
            env.store.radar().updateState(run.runId(), "running", "scoring", null);
            env.store.radar().recoverRuns();
            assertEquals("paused", env.service.status(run.runId()).state());
            env.worker.processNext();
            assertEquals(0, env.requests.get());
            env.service.resume(run.runId());
            env.worker.processNext();
            assertEquals("success", env.service.status(run.runId()).state());
        }
    }

    @Test
    void modelChangeDuringLastResponsePausesBeforePublishing() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}")) {
            env.save("https://a.test/1");
            var run =
                    env.service.submit(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "Asia/Shanghai", "change");
            var original = env.ai.get();
            env.onBlocks = () -> env.ai.save(new app.ai.AiConfig(original.host(), "changed", true));
            env.worker.processNext();
            assertEquals("paused", env.service.status(run.runId()).state());
            assertNull(env.store.radar().loadReport(run.runId()));
            env.onBlocks = () -> {};
            env.ai.save(original);
            int calls = env.requests.get();
            env.service.resume(run.runId());
            env.worker.processNext();
            assertEquals("success", env.service.status(run.runId()).state());
            assertEquals(calls, env.requests.get());
        }
    }

    @Test
    void persistenceFailureFailsRunRatherThanMasqueradingAsAnAnalysisFailure() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}");
                var broken = new app.store.FeedStore(directory.resolve("broken.db"), env.mapper);
                var sql = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("broken.db"));
                var statement = sql.createStatement()) {
            // Saving an analysis fails the way an unavailable disk would; reads keep working.
            statement.execute("CREATE TRIGGER analysis_unavailable BEFORE INSERT ON radar_analyses BEGIN SELECT"
                    + " RAISE(ABORT,'Storage unavailable'); END");
            broken.saveItems(
                    "/a",
                    List.of(new app.core.FeedItem(
                            "Saved title", "https://a.test/1", "body ".repeat(200), null, null, List.of())));
            var run = broken.radar()
                    .createRun(
                            new RadarTypes.RunRequest(
                                    LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),
                                    "Asia/Shanghai",
                                    "broken",
                                    env.ai.get(),
                                    Map.of()),
                            env.config,
                            env.profiles.all());
            try (var worker = new RadarWorker(
                    broken,
                    env.config,
                    env.ai,
                    new app.ai.OllamaClient(env.ai, env.mapper),
                    null,
                    env.mapper,
                    Clock.systemUTC())) {
                worker.processNext();
                assertEquals(
                        "failed", broken.radar().loadRun(run.runId()).status().state());
                assertNull(broken.radar().loadReport(run.runId()));
            }
        }
    }

    @Test
    void validBlocksSurviveAnInvalidNeighborBlock() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}")) {
            env.save("https://a.test/1");
            env.blocksResponse = "{\"blocks\":{\"summary\":[\"Valid summary\"],\"importance\":\"bad\"}}";
            var run =
                    env.service.submit(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "Asia/Shanghai", "blocks");
            env.worker.processNext();
            var report = env.store.radar().loadReport(run.runId());
            assertEquals(
                    List.of("Valid summary"),
                    report.stories().getFirst().blocks().get("summary"));
            assertEquals("partial", env.service.status(run.runId()).state());
        }
    }

    @Test
    void ordinaryFilteringDoesNotInflateBudgetFailureCount() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true,\"maxModelCalls\":3}")) {
            env.save("https://a.test/low1");
            env.save("https://a.test/low2");
            env.save("https://a.test/keep");
            env.scoring = p -> p.contains("/keep")
                    ? "{\"score\":8,\"reason\":\"r\",\"summary\":\"s\",\"tags\":[]}"
                    : "{\"score\":6,\"reason\":\"r\",\"summary\":\"s\",\"tags\":[]}";
            var run =
                    env.service.submit(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "Asia/Shanghai", "counts");
            env.worker.processNext();
            assertEquals(1, env.store.radar().loadReport(run.runId()).failures());
            assertEquals(
                    3, env.store.radar().loadReport(run.runId()).decisions().size());
        }
    }

    @Test
    void crashRecoveryChargesElapsedAttemptTime() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}")) {
            var run = env.service.submit(
                    LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "Asia/Shanghai", "crash-time");
            env.store.radar().updateState(run.runId(), "running", "scoring", null);
            env.store
                    .radar()
                    .saveCheckpoint(run.runId(), "attemptStartedAt", Long.toString(System.currentTimeMillis() - 60000));
            env.store.radar().recoverRuns();
            var recovered = env.store.radar().loadRun(run.runId());
            assertTrue(Long.parseLong(recovered.checkpoints().getOrDefault("elapsedMillis", "0")) >= 60000);
            assertEquals("paused", recovered.status().state());
        }
    }
}
