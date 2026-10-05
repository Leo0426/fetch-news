package app;

import app.core.Feed;
import app.core.FeedItem;
import app.core.RouteContext;
import app.core.RouteMatch;
import app.store.FeedStore;
import app.store.FetchLogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedFetcherPersistenceTest {
    @TempDir
    Path tempDir;

    @Test
    void persistenceFailureReturnsFeedAndRecordsStorageFailure() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper()) {
            @Override
            public synchronized int saveItems(String routePath, List<FeedItem> items) throws SQLException {
                throw new SQLException("database is read-only");
            }
        }) {
            Feed feed = feed();
            RouteContext context = new RouteContext("/news", Map.of(), Map.of());
            RouteMatch match = new RouteMatch(ignored -> feed, context);

            assertSame(feed, new FeedFetcher(store).fetch(match, context, "/news"));

            List<FetchLogEntry> logs = store.getRecentLogs("/news", 10);
            assertEquals(1, logs.size());
            assertEquals("error", logs.getFirst().status());
            assertTrue(logs.getFirst().errorDetail().contains("persistence failed"));
            assertTrue(logs.getFirst().errorDetail().contains("SQLException"));
            assertTrue(logs.getFirst().errorDetail().contains("database is read-only"));
            assertTrue(store.loadItems("/news").isEmpty());
        }
    }

    @Test
    void loggingFailureDoesNotRetryOrDiscardPersistedFeed() throws Exception {
        AtomicInteger logAttempts = new AtomicInteger();
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper()) {
            @Override
            public synchronized void logFetch(String routePath, Instant fetchedAt, long durationMs,
                    int itemCount, int newItemCount, String errorDetail) throws SQLException {
                logAttempts.incrementAndGet();
                throw new SQLException("fetch log is unavailable");
            }
        }) {
            Feed feed = feed();
            RouteContext context = new RouteContext("/news", Map.of(), Map.of());
            RouteMatch match = new RouteMatch(ignored -> feed, context);

            assertSame(feed, new FeedFetcher(store).fetch(match, context, "/news"));

            assertEquals(1, logAttempts.get());
            assertEquals(1, store.loadItems("/news").size());
        }
    }

    @Test
    void routeStorageFailureIsLoggedAsPersistenceFailure() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            SQLException failure = new SQLException("snapshot storage is unavailable");
            RouteContext context = new RouteContext("/news", Map.of(), Map.of());
            RouteMatch match = new RouteMatch(ignored -> { throw failure; }, context);

            assertSame(failure, assertThrows(SQLException.class,
                    () -> new FeedFetcher(store).fetch(match, context, "/news")));

            FetchLogEntry entry = store.getRecentLogs("/news", 10).getFirst();
            assertEquals("error", entry.status());
            assertTrue(entry.errorDetail().contains("persistence failed"));
            assertTrue(entry.errorDetail().contains("SQLException"));
            assertTrue(entry.errorDetail().contains("snapshot storage is unavailable"));
        }
    }

    private static Feed feed() {
        return new Feed("News", "https://example.com", "News", List.of(
                new FeedItem("Article", "https://example.com/article", "Body", null, null, List.of())));
    }
}
