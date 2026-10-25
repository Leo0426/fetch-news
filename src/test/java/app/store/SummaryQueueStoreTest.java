package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.auth.UserAccount;
import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SummaryQueueStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void queuesAndClaimsStoredArticleWithFullContent() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            FeedItem item = item("first");
            Instant queuedAt = Instant.parse("2026-10-05T01:00:00Z");
            Instant startedAt = queuedAt.plusSeconds(10);
            store.saveItems("/articles", List.of(item));

            assertTrue(store.summaryQueue().enqueue(item.link(), queuedAt, false));
            SummaryStatus queued = status(store, item.link());
            assertTrue(queued.queued());
            assertEquals(queuedAt, queued.startedAt());
            assertEquals("", queued.model());
            assertNull(queued.completedAt());
            assertEquals("—", queued.formattedDuration());

            assertEquals(item, store.summaryQueue().claimNext(startedAt, "local-model").item());
            SummaryStatus running = status(store, item.link());
            assertTrue(running.running());
            assertEquals(startedAt, running.startedAt());
            assertEquals("local-model", running.model());
            assertNull(store.summaryQueue().claimNext(startedAt, "local-model"));
            assertEquals(item, store.loadItem(item.link()));
            assertNull(store.loadItem("https://example.com/absent"));
        }
    }

    @Test
    void failedSummaryRequiresExplicitRetryAndClearsPreviousAttempt() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            FeedItem item = item("retry");
            Instant queuedAt = Instant.parse("2026-10-05T01:00:00Z");
            store.saveItems("/articles", List.of(item));
            assertTrue(store.summaryQueue().enqueue(item.link(), queuedAt, false));
            assertFalse(store.summaryQueue().enqueue(item.link(), queuedAt, true));
            store.summaryQueue().claimNext(queuedAt.plusSeconds(1), "old-model");
            assertFalse(store.summaryQueue().enqueue(item.link(), queuedAt, true));
            store.summaryQueue().markFinished(item.link(), queuedAt.plusSeconds(1), queuedAt.plusSeconds(4),
                    "old-model", "model unavailable");
            SummaryStatus failed = status(store, item.link());
            assertFalse(store.summaryQueue().enqueue(item.link(), queuedAt.plusSeconds(5), false));
            assertEquals(failed, status(store, item.link()));

            assertTrue(store.summaryQueue().enqueue(item.link(), queuedAt.plusSeconds(10), true));
            SummaryStatus retried = status(store, item.link());
            assertTrue(retried.queued());
            assertEquals(queuedAt.plusSeconds(10), retried.startedAt());
            assertEquals("", retried.model());
            assertNull(retried.completedAt());
            assertNull(retried.durationMs());
            assertNull(retried.errorDetail());
            assertFalse(store.summaryQueue().enqueue(item.link(), queuedAt.plusSeconds(11), true));

            assertEquals(item, store.summaryQueue().claimNext(queuedAt.plusSeconds(12), "new-model").item());
            store.summaryQueue().markFinished(item.link(), queuedAt.plusSeconds(12), queuedAt.plusSeconds(15),
                    "new-model", null);
            assertFalse(store.summaryQueue().enqueue(item.link(), queuedAt.plusSeconds(20), true));
        }
    }

    @Test
    void onlyStoredArticlesWithoutCachedSummariesCanEnterOrLeaveQueue() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            Instant time = Instant.parse("2026-10-05T01:00:00Z");
            FeedItem cached = item("cached");
            FeedItem cachedAfterQueue = item("cached-after-queue");
            FeedItem blankSummary = item("blank-summary");
            store.saveItems("/articles", List.of(cached, cachedAfterQueue, blankSummary));
            assertFalse(store.summaryQueue().enqueue("https://example.com/absent", time, true));
            assertFalse(store.summaryQueue().enqueue(null, time, true));
            assertFalse(store.summaryQueue().enqueue("", time, true));
            store.aiCache().saveSummary(cached.link(), "已有摘要", "old-model");
            store.summaryQueue().markFinished(cached.link(), time, time, "old-model", "late failure");
            assertFalse(store.summaryQueue().enqueue(cached.link(), time, true));

            assertTrue(store.summaryQueue().enqueue(cachedAfterQueue.link(), time, false));
            store.aiCache().saveSummary(cachedAfterQueue.link(), "已缓存", "old-model");
            assertTrue(store.summaryQueue().enqueue(blankSummary.link(), time.plusSeconds(1), false));
            store.aiCache().saveSummary(blankSummary.link(), " \n\t\r", "old-model");

            assertEquals(blankSummary, store.summaryQueue().claimNext(time.plusSeconds(2), "local-model").item());
            assertNull(store.summaryQueue().claimNext(time.plusSeconds(3), "local-model"));
        }
    }

    @Test
    void claimsByPreciseQueueTimeWithStableTies() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            FeedItem first = item("first");
            FeedItem second = item("second");
            FeedItem third = item("third");
            FeedItem fourth = item("fourth");
            Instant time = Instant.parse("2026-10-05T01:00:00.123Z");
            store.saveItems("/articles", List.of(fourth, third, second, first));
            store.summaryQueue().enqueue(third.link(), time.plusNanos(1), false);
            store.summaryQueue().enqueue(first.link(), time, false);
            store.summaryQueue().enqueue(second.link(), time, false);
            store.summaryQueue().enqueue(fourth.link(), time.plusMillis(1), false);

            List<FeedItem> claimed = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                claimed.add(store.summaryQueue().claimNext(time.plusSeconds(10), "local-model").item());
            }
            assertEquals(List.of(first, second, third, fourth), claimed);
            assertNull(store.summaryQueue().claimNext(time.plusSeconds(10), "local-model"));
        }
    }

    @Test
    void pendingQueueSurvivesRestartAndInterruptedRunsBecomeRetryable() throws Exception {
        Path database = tempDir.resolve("feed.db");
        Instant time = Instant.parse("2026-10-05T01:00:00Z");
        FeedItem queued = item("queued");
        FeedItem interrupted = item("interrupted");
        FeedItem persisted = item("persisted");
        FeedItem finished = item("finished");
        SummaryStatus finishedStatus;
        SummaryStatus queuedStatus;
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.saveItems("/articles", List.of(queued, interrupted, persisted, finished));
            store.summaryQueue().enqueue(interrupted.link(), time.minusSeconds(2), false);
            store.summaryQueue().enqueue(persisted.link(), time.minusSeconds(1), false);
            store.summaryQueue().claimNext(time, "old-model");
            store.summaryQueue().claimNext(time, "old-model");
            assertTrue(store.summaryQueue().enqueue(queued.link(), time, false));
            queuedStatus = status(store, queued.link());
            store.aiCache().saveSummary(persisted.link(), "已保存但尚未写入任务完成状态", "old-model");
            store.summaryQueue().markFinished(finished.link(), time, time.plusSeconds(1), "old-model", null);
            finishedStatus = status(store, finished.link());
        }

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertTrue(status(store, interrupted.link()).running());
            store.summaryQueue().recoverInterrupted(time.plusSeconds(20));
            assertEquals(queuedStatus, status(store, queued.link()));
            assertEquals(finishedStatus, status(store, finished.link()));
            SummaryStatus recovered = status(store, interrupted.link());
            assertTrue(recovered.error());
            assertEquals(time.plusSeconds(20), recovered.completedAt());
            assertEquals("old-model", recovered.model());
            assertEquals("Task interrupted by application restart; retry to continue.", recovered.errorDetail());
            assertNull(recovered.durationMs());
            SummaryStatus success = status(store, persisted.link());
            assertTrue(success.success());
            assertNull(success.errorDetail());
            assertEquals(time.plusSeconds(20), success.completedAt());
            store.summaryQueue().recoverInterrupted(time.plusSeconds(30));
            assertEquals(recovered, status(store, interrupted.link()));
            assertEquals(success, status(store, persisted.link()));
            assertEquals(queued, store.summaryQueue().claimNext(time.plusSeconds(40), "new-model").item());
            assertFalse(store.summaryQueue().enqueue(interrupted.link(), time.plusSeconds(50), false));
            assertTrue(store.summaryQueue().enqueue(interrupted.link(), time.plusSeconds(50), true));
            assertEquals(interrupted, store.summaryQueue().claimNext(time.plusSeconds(60), "new-model").item());
        }
    }

    @Test
    void concurrentClaimersCannotReceiveTheSameArticle() throws Exception {
        Path database = tempDir.resolve("feed.db");
        Instant time = Instant.parse("2026-10-05T01:00:00Z");
        List<FeedItem> items = new ArrayList<>();
        for (int index = 0; index < 20; index++) items.add(item("concurrent-" + index));
        try (FeedStore first = new FeedStore(database, new ObjectMapper());
             FeedStore second = new FeedStore(database, new ObjectMapper());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            first.saveItems("/articles", items);
            for (FeedItem item : items) first.summaryQueue().enqueue(item.link(), time, false);
            CountDownLatch start = new CountDownLatch(1);
            var one = executor.submit(() -> claimAll(first, start, time));
            var two = executor.submit(() -> claimAll(second, start, time));
            start.countDown();
            List<FeedItem> claimed = new ArrayList<>(one.get());
            claimed.addAll(two.get());
            assertEquals(items.size(), claimed.size());
            assertEquals(new HashSet<>(items), new HashSet<>(claimed));
        }
    }

    @Test
    void interactiveRequestsJumpAheadAndPromotePendingBackgroundTasks() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            FeedItem a = item("a"), b = item("b"), c = item("c"), d = item("d");
            Instant time = Instant.parse("2026-10-05T01:00:00Z");
            store.saveItems("/articles", List.of(a, b, c, d));
            store.summaryQueue().enqueue(a.link(), time, false);
            store.summaryQueue().enqueue(b.link(), time.plusSeconds(1), false);
            store.summaryQueue().enqueue(c.link(), time.plusSeconds(2), false);
            assertTrue(store.summaryQueue().enqueue(b.link(), time.plusSeconds(3), true, SummaryQueue.Priority.INTERACTIVE));
            store.summaryQueue().enqueue(d.link(), time.plusSeconds(4), true, SummaryQueue.Priority.INTERACTIVE);

            assertEquals(List.of(b, d, a, c), claimAll(store, new CountDownLatch(0), time.plusSeconds(5)));
        }
    }

    @Test
    void dropsStaleOrAlreadyReadBackgroundTasksButKeepsInteractiveOnes() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            FeedItem stale = item("stale"), read = item("read"), fresh = item("fresh"), asked = item("asked");
            Instant time = Instant.parse("2026-10-05T01:00:00Z");
            Instant claimAt = time.plus(SummaryQueue.BACKGROUND_TTL).plusSeconds(60);
            store.saveItems("/articles", List.of(stale, read, fresh, asked));
            store.summaryQueue().enqueue(stale.link(), time, false);
            store.summaryQueue().enqueue(asked.link(), time, false, SummaryQueue.Priority.INTERACTIVE);
            store.summaryQueue().enqueue(read.link(), claimAt, false);
            store.summaryQueue().enqueue(fresh.link(), claimAt, false);
            store.reading().markRead(UserAccount.OWNER_ID, read.link());

            assertEquals(List.of(asked, fresh), claimAll(store, new CountDownLatch(0), claimAt));
            assertNull(status(store, stale.link()));
            assertNull(status(store, read.link()));
        }
    }

    @Test
    void retriesARunningTaskOnlyOnce() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            FeedItem item = item("flaky");
            Instant time = Instant.parse("2026-10-05T01:00:00Z");
            store.saveItems("/articles", List.of(item));
            store.summaryQueue().enqueue(item.link(), time, true, SummaryQueue.Priority.INTERACTIVE);
            store.summaryQueue().claimNext(time, "local-model");

            assertTrue(store.summaryQueue().retryOnce(item.link(), time.plusSeconds(1)));
            assertTrue(status(store, item.link()).queued());
            assertEquals(item, store.summaryQueue().claimNext(time.plusSeconds(2), "local-model").item());
            assertFalse(store.summaryQueue().retryOnce(item.link(), time.plusSeconds(3)));
        }
    }

    @Test
    void restartRequeuesInteractiveTasksAndFailsBackgroundOnes() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            FeedItem asked = item("asked"), auto = item("auto");
            Instant time = Instant.parse("2026-10-05T01:00:00Z");
            store.saveItems("/articles", List.of(asked, auto));
            store.summaryQueue().enqueue(asked.link(), time, true, SummaryQueue.Priority.INTERACTIVE);
            store.summaryQueue().enqueue(auto.link(), time, false);
            store.summaryQueue().claimNext(time, "local-model");
            store.summaryQueue().claimNext(time, "local-model");

            store.summaryQueue().recoverInterrupted(time.plusSeconds(10));

            assertTrue(status(store, asked.link()).queued());
            assertTrue(status(store, auto.link()).error());
        }
    }

    @Test
    void upgradingFromUnprioritizedQueueDropsPendingAndFailedTasks() throws Exception {
        Path database = tempDir.resolve("legacy.db");
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE ai_summary_runs (item_link TEXT PRIMARY KEY, status TEXT NOT NULL,
                        started_at TEXT NOT NULL, completed_at TEXT, duration_ms INTEGER, model TEXT, error_detail TEXT)""");
            statement.execute("""
                    INSERT INTO ai_summary_runs(item_link, status, started_at) VALUES
                        ('https://example.com/q', 'queued', '2026-10-05T01:00:00Z'),
                        ('https://example.com/e', 'error', '2026-10-05T01:00:00Z'),
                        ('https://example.com/s', 'success', '2026-10-05T01:00:00Z')""");
        }
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            FeedItem queued = item("q"), failed = item("e"), done = item("s");
            store.saveItems("/articles", List.of(queued, failed, done));

            assertNull(status(store, queued.link()));
            assertNull(status(store, failed.link()));
            assertTrue(status(store, done.link()).success());
        }
    }

    private static List<FeedItem> claimAll(FeedStore store, CountDownLatch start, Instant time) throws Exception {
        start.await();
        List<FeedItem> result = new ArrayList<>();
        SummaryQueue.Claim claim;
        while ((claim = store.summaryQueue().claimNext(time, "local-model")) != null) result.add(claim.item());
        return result;
    }

    private static SummaryStatus status(FeedStore store, String link) throws Exception {
        return store.summaryQueue().statuses(List.of(link)).get(link);
    }

    private static FeedItem item(String id) {
        return new FeedItem("Article " + id, "https://example.com/" + id, "<p>Full article body " + id + "</p>",
                Instant.parse("2026-10-03T06:00:00.123456789Z"), "Author", List.of("AI", "本地阅读"));
    }
}
