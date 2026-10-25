package app.radar;

import app.ai.AiConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadarSchedulerTest {
    @TempDir
    Path directory;

    @Test
    void repeatedTicksAndRestartSharePersistedKeyAndCatchUpOnlyLatestWindow() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true,\"scheduleCron\":\"0 0 8 * * *\"}")) {
            Clock clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneId.of("UTC"));
            new RadarScheduler(env.config, env.service, clock).tick();
            var first = env.store.radar().latestRun("2026-10-18", "Asia/Shanghai", false);
            assertNotNull(first);
            new RadarScheduler(env.config, env.service, clock).tick();
            assertEquals(
                    1,
                    env.store
                            .radar()
                            .latestRun("2026-10-18", "Asia/Shanghai", false)
                            .status()
                            .version());
            assertNull(env.store.radar().latestRun("2026-10-17", "Asia/Shanghai", false));
            env.ai.save(new AiConfig(null, null, false));
            new RadarScheduler(env.config, env.service, Clock.offset(clock, Duration.ofDays(1))).tick();
            assertNull(env.store.radar().latestRun("2026-10-19", "Asia/Shanghai", false));
        }
    }

    @Test
    void daylightSavingGapUsesPreviousActualDueWindow() throws Exception {
        try (var env = new RadarTestEnvironment(
                directory, "{\"enabled\":true,\"zoneId\":\"America/New_York\",\"scheduleCron\":\"0 30 2 * * *\"}")) {
            var clock = Clock.fixed(Instant.parse("2026-03-08T12:00:00Z"), ZoneId.of("UTC"));
            new RadarScheduler(env.config, env.service, clock).tick();
            assertNotNull(env.store.radar().latestRun("2026-03-06", "America/New_York", false));
            assertNull(env.store.radar().latestRun("2026-03-07", "America/New_York", false));
        }
    }
}
