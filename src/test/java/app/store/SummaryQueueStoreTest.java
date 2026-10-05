package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
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

            assertTrue(store.enqueueSummary(item.link(), queuedAt, false));
            SummaryStatus queued = status(store, item.link());
            assertTrue(queued.queued());
            assertEquals(queuedAt, queued.startedAt());
            assertEquals("", queued.model());
            assertNull(queued.completedAt());
            assertEquals("—", queued.formattedDuration());

            assertEquals(item, store.claimNextSummary(startedAt, "local-model"));
            SummaryStatus running = status(store, item.link());
            assertTrue(running.running());
            assertEquals(startedAt, running.startedAt());
            assertEquals("local-model", running.model());
            assertNull(store.claimNextSummary(startedAt, "local-model"));
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
            assertTrue(store.enqueueSummary(item.link(), queuedAt, false));
            assertFalse(store.enqueueSummary(item.link(), queuedAt, true));
            store.claimNextSummary(queuedAt.plusSeconds(1), "old-model");
            assertFalse(store.enqueueSummary(item.link(), queuedAt, true));
            store.markSummaryFinished(item.link(), queuedAt.plusSeconds(1), queuedAt.plusSeconds(4),
                    "old-model", "model unavailable");
            SummaryStatus failed = status(store, item.link());
            assertFalse(store.enqueueSummary(item.link(), queuedAt.plusSeconds(5), false));
            assertEquals(failed, status(store, item.link()));

            assertTrue(store.enqueueSummary(item.link(), queuedAt.plusSeconds(10), true));
            SummaryStatus retried = status(store, item.link());
            assertTrue(retried.queued());
            assertEquals(queuedAt.plusSeconds(10), retried.startedAt());
            assertEquals("", retried.model());
            assertNull(retried.completedAt());
            assertNull(retried.durationMs());
            assertNull(retried.errorDetail());
            assertFalse(store.enqueueSummary(item.link(), queuedAt.plusSeconds(11), true));

            assertEquals(item, store.claimNextSummary(queuedAt.plusSeconds(12), "new-model"));
            store.markSummaryFinished(item.link(), queuedAt.plusSeconds(12), queuedAt.plusSeconds(15),
                    "new-model", null);
            assertFalse(store.enqueueSummary(item.link(), queuedAt.plusSeconds(20), true));
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
            assertFalse(store.enqueueSummary("https://example.com/absent", time, true));
            assertFalse(store.enqueueSummary(null, time, true));
            assertFalse(store.enqueueSummary("", time, true));
            store.saveSummary(cached.link(), "已有摘要", "old-model");
            store.markSummaryFinished(cached.link(), time, time, "old-model", "late failure");
            assertFalse(store.enqueueSummary(cached.link(), time, true));

            assertTrue(store.enqueueSummary(cachedAfterQueue.link(), time, false));
            store.saveSummary(cachedAfterQueue.link(), "已缓存", "old-model");
            assertTrue(store.enqueueSummary(blankSummary.link(), time.plusSeconds(1), false));
            store.saveSummary(blankSummary.link(), " \n\t\r", "old-model");

            assertEquals(blankSummary, store.claimNextSummary(time.plusSeconds(2), "local-model"));
            assertNull(store.claimNextSummary(time.plusSeconds(3), "local-model"));
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
            store.enqueueSummary(third.link(), time.plusNanos(1), false);
            store.enqueueSummary(first.link(), time, false);
            store.enqueueSummary(second.link(), time, false);
            store.enqueueSummary(fourth.link(), time.plusMillis(1), false);

            List<FeedItem> claimed = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                claimed.add(store.claimNextSummary(time.plusSeconds(10), "local-model"));
            }
            assertEquals(List.of(first, second, third, fourth), claimed);
            assertNull(store.claimNextSummary(time.plusSeconds(10), "local-model"));
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
            assertTrue(store.enqueueSummary(queued.link(), time, false));
            queuedStatus = status(store, queued.link());
            store.markSummaryStarted(interrupted.link(), time, "old-model");
            store.markSummaryStarted(persisted.link(), time, "old-model");
            store.saveSummary(persisted.link(), "已保存但尚未写入任务完成状态", "old-model");
            store.markSummaryFinished(finished.link(), time, time.plusSeconds(1), "old-model", null);
            finishedStatus = status(store, finished.link());
        }

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertTrue(status(store, interrupted.link()).running());
            store.recoverInterruptedSummaries(time.plusSeconds(20));
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
            store.recoverInterruptedSummaries(time.plusSeconds(30));
            assertEquals(recovered, status(store, interrupted.link()));
            assertEquals(success, status(store, persisted.link()));
            assertEquals(queued, store.claimNextSummary(time.plusSeconds(40), "new-model"));
            assertFalse(store.enqueueSummary(interrupted.link(), time.plusSeconds(50), false));
            assertTrue(store.enqueueSummary(interrupted.link(), time.plusSeconds(50), true));
            assertEquals(interrupted, store.claimNextSummary(time.plusSeconds(60), "new-model"));
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
            for (FeedItem item : items) first.enqueueSummary(item.link(), time, false);
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

    private static List<FeedItem> claimAll(FeedStore store, CountDownLatch start, Instant time) throws Exception {
        start.await();
        List<FeedItem> result = new ArrayList<>();
        FeedItem item;
        while ((item = store.claimNextSummary(time, "local-model")) != null) result.add(item);
        return result;
    }

    private static SummaryStatus status(FeedStore store, String link) throws Exception {
        return store.loadSummaryStatuses(List.of(link)).get(link);
    }

    private static FeedItem item(String id) {
        return new FeedItem("Article " + id, "https://example.com/" + id, "<p>Full article body " + id + "</p>",
                Instant.parse("2026-10-03T06:00:00.123456789Z"), "Author", List.of("AI", "本地阅读"));
    }
}
