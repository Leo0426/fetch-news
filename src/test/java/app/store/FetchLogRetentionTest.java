package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FetchLogRetentionTest {
    @TempDir
    Path tempDir;

    @Test
    void pruneKeepsRecentEntriesAndEachRoutesLatestAndLatestSuccess() throws Exception {
        Instant now = Instant.now();
        Instant old = now.minus(Duration.ofDays(60));
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            FetchStateStore logs = store.fetchState();
            logs.logFetch("/busy", old, 1, 1, 0, null);
            logs.logFetch("/busy", old.plusSeconds(1), 1, 1, 0, null);
            logs.logFetch("/busy", now, 1, 1, 0, null);
            logs.logFetch("/stale", old, 1, 1, 0, null);
            logs.logFetch("/stale", old.plusSeconds(1), 1, 1, 0, null);
            logs.logFetch("/stale", old.plusSeconds(2), 1, 0, 0, "boom");

            assertEquals(3, logs.pruneFetchLog(now.minus(Duration.ofDays(30))));

            assertEquals(List.of(now), times(logs.getRecentLogs("/busy", 10)));
            assertEquals(List.of(old.plusSeconds(2), old.plusSeconds(1)), times(logs.getRecentLogs("/stale", 10)));
            assertEquals(old.plusSeconds(1), logs.lastFetchedAt("/stale"));
            assertEquals("boom", logs.latestFetchLog("/stale").errorDetail());
        }
    }

    @Test
    void todayCountsStartAtLocalMidnight() throws Exception {
        Instant midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            FetchStateStore logs = store.fetchState();
            logs.logFetch("/a", midnight.minusSeconds(1), 1, 1, 0, null);
            logs.logFetch("/a", midnight.plusSeconds(1), 1, 1, 0, null);
            logs.logFetch("/a", midnight.plusSeconds(2), 1, 0, 0, "boom");

            assertEquals(2, logs.countLogsToday());
            assertEquals(1, logs.countErrorsToday());
        }
    }

    private static List<Instant> times(List<FetchLogEntry> entries) {
        return entries.stream().map(FetchLogEntry::fetchedAt).toList();
    }
}
