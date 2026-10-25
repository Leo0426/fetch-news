package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedRefreshStoreTest {
    private static final String FEED_URL = "https://example.com/feed.xml";
    private static final Instant CHECKED_AT = Instant.parse("2026-10-03T10:15:30.123456789Z");

    @TempDir
    Path tempDir;

    @Test
    void missingSnapshotAndRefreshStateReturnNull() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            assertNull(store.fetchState().loadHttpFeedSnapshot(FEED_URL));
            assertNull(store.fetchState().loadFeedRefreshState("/blog", FEED_URL));
            assertTrue(store.fetchState().loadFeedRefreshStates().isEmpty());
        }
    }

    @Test
    void snapshotsAndRefreshStateSurviveRestart() throws Exception {
        Path database = tempDir.resolve("feed.db");
        HttpFeedSnapshot snapshot = new HttpFeedSnapshot(
                FEED_URL, "<rss>中文正文</rss>", "W/\"version-1\"", "Sat, 03 Oct 2026 10:00:00 GMT");
        FeedRefreshState state = new FeedRefreshState(
                "/blog", FEED_URL, CHECKED_AT, Instant.parse("2026-10-03T10:00:00Z"),
                Instant.parse("2026-10-03T09:00:00Z"), Instant.parse("2026-10-03T10:17:30Z"),
                2, 503, 4_294_967_296L, 1250, "Service unavailable");

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.fetchState().saveHttpFeedSnapshot(snapshot);
            store.fetchState().saveFeedRefreshState(state);
        }

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertEquals(snapshot, store.fetchState().loadHttpFeedSnapshot(FEED_URL));
            assertEquals(state, store.fetchState().loadFeedRefreshState("/blog", FEED_URL));
            assertEquals(List.of(state), store.fetchState().loadFeedRefreshStates());
        }
    }

    @Test
    void updatingSnapshotReplacesBodyAndClearsMissingValidators() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            store.fetchState().saveHttpFeedSnapshot(new HttpFeedSnapshot(FEED_URL, "old body", "old-tag", "old-date"));
            HttpFeedSnapshot replacement = new HttpFeedSnapshot(FEED_URL, "new body", null, null);

            store.fetchState().saveHttpFeedSnapshot(replacement);

            assertEquals(replacement, store.fetchState().loadHttpFeedSnapshot(FEED_URL));
        }
    }

    @Test
    void nullBodyCannotReplaceValidSnapshot() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            HttpFeedSnapshot snapshot = new HttpFeedSnapshot(FEED_URL, "valid body", "tag", null);
            store.fetchState().saveHttpFeedSnapshot(snapshot);

            assertThrows(SQLException.class, () -> store.fetchState().saveHttpFeedSnapshot(
                    new HttpFeedSnapshot(FEED_URL, null, "invalid-tag", null)));

            assertEquals(snapshot, store.fetchState().loadHttpFeedSnapshot(FEED_URL));
        }
    }

    @Test
    void snapshotDeletionLeavesOtherUrlsAndRefreshStateIntact() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            HttpFeedSnapshot other = new HttpFeedSnapshot(FEED_URL + "?edition=other", "other body", null, null);
            FeedRefreshState state = new FeedRefreshState(
                    "/blog", FEED_URL, CHECKED_AT, CHECKED_AT, CHECKED_AT, null, 0, 200, 10, 20, null);
            store.fetchState().saveHttpFeedSnapshot(new HttpFeedSnapshot(FEED_URL, "body", "tag", null));
            store.fetchState().saveHttpFeedSnapshot(other);
            store.fetchState().saveFeedRefreshState(state);

            store.fetchState().deleteHttpFeedSnapshot(FEED_URL);
            store.fetchState().deleteHttpFeedSnapshot(FEED_URL);

            assertNull(store.fetchState().loadHttpFeedSnapshot(FEED_URL));
            assertEquals(other, store.fetchState().loadHttpFeedSnapshot(other.url()));
            assertEquals(state, store.fetchState().loadFeedRefreshState("/blog", FEED_URL));
        }
    }

    @Test
    void changingFeedUrlIsolatesRefreshStateForEachSource() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            FeedRefreshState original = new FeedRefreshState(
                    "/blog", FEED_URL, CHECKED_AT, null, null, CHECKED_AT.plusSeconds(60),
                    1, 429, 0, 10, "Too many requests");
            String nextUrl = "https://other.example.com/feed.xml";
            FeedRefreshState replacement = new FeedRefreshState(
                    "/blog", nextUrl, CHECKED_AT, CHECKED_AT, CHECKED_AT, null, 0, 200, 100, 50, null);
            FeedRefreshState otherPath = new FeedRefreshState(
                    "/other", FEED_URL, CHECKED_AT, CHECKED_AT, CHECKED_AT, null, 0, 304, 0, 5, null);
            store.fetchState().saveFeedRefreshState(original);

            assertNull(store.fetchState().loadFeedRefreshState("/blog", nextUrl));

            store.fetchState().saveFeedRefreshState(replacement);
            store.fetchState().saveFeedRefreshState(otherPath);

            assertEquals(original, store.fetchState().loadFeedRefreshState("/blog", FEED_URL));
            assertEquals(replacement, store.fetchState().loadFeedRefreshState("/blog", nextUrl));
            List<FeedRefreshState> states = store.fetchState().loadFeedRefreshStates();
            assertEquals(3, states.size());
            assertTrue(states.containsAll(List.of(original, replacement, otherPath)));
        }
    }

    @Test
    void nullableRefreshFieldsSurviveRestart() throws Exception {
        Path database = tempDir.resolve("feed.db");
        FeedRefreshState state = new FeedRefreshState(
                "/blog", FEED_URL, null, null, null, null, 0, 0, 0, 0, null);
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.fetchState().saveFeedRefreshState(new FeedRefreshState(
                    "/blog", FEED_URL, CHECKED_AT, CHECKED_AT, CHECKED_AT, CHECKED_AT.plusSeconds(60),
                    1, 503, 128, 75, "Unavailable"));
            store.fetchState().saveFeedRefreshState(state);
        }

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertEquals(state, store.fetchState().loadFeedRefreshState("/blog", FEED_URL));
            assertEquals(List.of(state), store.fetchState().loadFeedRefreshStates());
        }
    }

    @Test
    void lateCompletionForOldSourceDoesNotEraseNewSourceBackoffAfterRestart() throws Exception {
        Path database = tempDir.resolve("feed.db");
        String nextUrl = "https://other.example.com/feed.xml";
        FeedRefreshState nextSource = new FeedRefreshState(
                "/blog", nextUrl, CHECKED_AT, null, null, CHECKED_AT.plusSeconds(3600),
                3, 429, 0, 30, "Too many requests");
        FeedRefreshState oldSource = new FeedRefreshState(
                "/blog", FEED_URL, CHECKED_AT.plusSeconds(10), CHECKED_AT.plusSeconds(10),
                CHECKED_AT.plusSeconds(10), null, 0, 200, 100, 10_000, null);
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.fetchState().saveFeedRefreshState(nextSource);
            store.fetchState().saveFeedRefreshState(oldSource);
        }

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertEquals(nextSource, store.fetchState().loadFeedRefreshState("/blog", nextUrl));
            assertEquals(oldSource, store.fetchState().loadFeedRefreshState("/blog", FEED_URL));
        }
    }

    @Test
    void contentFingerprintSurvivesRestartWithoutHttpSnapshot() throws Exception {
        Path database = tempDir.resolve("feed.db");
        FeedRefreshState state = new FeedRefreshState(
                "/blog", FEED_URL, CHECKED_AT, CHECKED_AT, CHECKED_AT, null,
                0, 200, 128, 30, null, "feed-content-fingerprint");
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.fetchState().saveFeedRefreshState(state);
        }

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertNull(store.fetchState().loadHttpFeedSnapshot(FEED_URL));
            assertEquals("feed-content-fingerprint", store.fetchState().loadFeedRefreshState("/blog", FEED_URL).contentFingerprint());
            assertEquals(List.of(state), store.fetchState().loadFeedRefreshStates());
        }
    }

    @Test
    void updatingStateCanReplaceAndClearContentFingerprint() throws Exception {
        Path database = tempDir.resolve("feed.db");
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.fetchState().saveFeedRefreshState(new FeedRefreshState(
                    "/blog", FEED_URL, CHECKED_AT, CHECKED_AT, CHECKED_AT, null,
                    0, 200, 128, 30, null, "first-fingerprint"));
            store.fetchState().saveFeedRefreshState(new FeedRefreshState(
                    "/blog", FEED_URL, CHECKED_AT, CHECKED_AT, CHECKED_AT, null,
                    0, 200, 256, 40, null, "second-fingerprint"));
            assertEquals("second-fingerprint", store.fetchState().loadFeedRefreshState("/blog", FEED_URL).contentFingerprint());

            store.fetchState().saveFeedRefreshState(new FeedRefreshState(
                    "/blog", FEED_URL, CHECKED_AT, CHECKED_AT, CHECKED_AT, null,
                    0, 200, 256, 40, null));
        }

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertNull(store.fetchState().loadFeedRefreshState("/blog", FEED_URL).contentFingerprint());
            assertNull(store.fetchState().loadFeedRefreshStates().getFirst().contentFingerprint());
        }
    }

    @Test
    void openingExistingDatabasePreservesStoredItems() throws Exception {
        Path database = tempDir.resolve("feed.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE feed_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        route_path TEXT NOT NULL,
                        item_link TEXT NOT NULL,
                        title TEXT,
                        description TEXT,
                        author TEXT,
                        pub_date TEXT,
                        categories TEXT,
                        first_seen_at TEXT NOT NULL,
                        last_seen_at TEXT NOT NULL,
                        UNIQUE(item_link)
                    )
                    """);
            statement.execute("""
                    INSERT INTO feed_items
                        (route_path, item_link, title, description, author, pub_date,
                         categories, first_seen_at, last_seen_at)
                    VALUES ('/blog', 'https://example.com/article', 'Existing article',
                            'Existing body', 'Existing author', '2026-10-03T09:00:00Z',
                            '["News"]', '2026-10-03T09:01:00Z', '2026-10-03T09:02:00Z')
                    """);
        }

        FeedItem existingItem = new FeedItem(
                "Existing article", "https://example.com/article", "Existing body",
                Instant.parse("2026-10-03T09:00:00Z"), "Existing author", List.of("News"));
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.fetchState().saveHttpFeedSnapshot(new HttpFeedSnapshot(FEED_URL, "<rss/>", null, null));
            store.fetchState().saveFeedRefreshState(new FeedRefreshState(
                    "/blog", FEED_URL, CHECKED_AT, CHECKED_AT, CHECKED_AT, null, 0, 200, 6, 20, null));

            assertEquals(List.of(existingItem), store.loadItems("/blog"));
        }

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertEquals(List.of(existingItem), store.loadItems("/blog"));
            assertEquals("<rss/>", store.fetchState().loadHttpFeedSnapshot(FEED_URL).body());
        }
    }
}
