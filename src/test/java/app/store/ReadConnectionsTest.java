package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadConnectionsTest {
    @TempDir
    Path tempDir;

    @Test
    void pageQueriesDoNotWaitForTheStoreLock() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            store.saveItems("/a", List.of(item("Lock free", "https://example.com/1")));
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread writer = Thread.startVirtualThread(() -> {
                synchronized (store) {
                    held.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            try {
                assertTrue(held.await(2, TimeUnit.SECONDS));
                CompletableFuture<Integer> reads = CompletableFuture.supplyAsync(() -> {
                    try {
                        return store.items().loadRecentItems(Duration.ofHours(1)).size()
                                + store.searchIndex().search("Lock", 10).size();
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
                assertEquals(2, reads.get(2, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                writer.join(2000);
            }
        }
    }

    @Test
    void readsSeeCommittedWritesAndFailAfterClose() throws Exception {
        FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper());
        assertEquals(0, store.items().loadRecentItems(Duration.ofHours(1)).size());
        store.saveItems("/a", List.of(item("First", "https://example.com/1")));
        assertEquals(1, store.items().loadRecentItems(Duration.ofHours(1)).size());

        store.close();

        assertThrows(SQLException.class, () -> store.items().loadRecentItems(Duration.ofHours(1)));
    }

    private static FeedItem item(String title, String link) {
        return new FeedItem(title, link, "body", Instant.now(), null, List.of(), null);
    }
}
