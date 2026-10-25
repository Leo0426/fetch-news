package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.auth.UserAccount;
import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnreadPageTest {
    @TempDir
    Path tempDir;

    @Test
    void includesUnreadItemsOlderThanSevenDaysAndExcludesReadItems() throws Exception {
        Path database = tempDir.resolve("feed.db");
        String unreadLink = "https://example.com/old-unread";
        String readLink = "https://example.com/old-read";
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.saveItems("/articles", List.of(
                    item("Old unread", unreadLink, "Saved for later"),
                    item("Old read", readLink, "Already finished")));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE feed_items SET first_seen_at = '2020-01-01T00:00:00Z'");
            }
            store.reading().markRead(UserAccount.OWNER_ID, readLink);

            UnreadPage page = store.unread().loadUnreadPage(UserAccount.OWNER_ID, null, null, false, null, 50);

            assertEquals(List.of(unreadLink), page.items().stream().map(StoredItem::link).toList());
            assertEquals(1, page.totalCount());
            assertEquals(List.of("/articles"), page.sources());
            assertNull(page.nextCursor());
        }
    }

    @Test
    void pagesThroughEqualTimestampsWithoutGapsWhenNewItemsArriveAndPreviousPageIsRead() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            List<FeedItem> originals = IntStream.range(0, 1_200)
                    .mapToObj(i -> item("Article " + i, "https://example.com/article/" + i, "Body"))
                    .toList();
            store.saveItems("/articles", originals);
            UnreadPage page = store.unread().loadUnreadPage(UserAccount.OWNER_ID, null, null, false, null, 100);
            assertEquals(1_200, page.totalCount());
            assertEquals(1, page.items().stream().map(StoredItem::firstSeenAt).distinct().count());
            assertNotNull(page.nextCursor());

            List<String> visited = new ArrayList<>(page.items().stream().map(StoredItem::link).toList());
            store.reading().markAllRead(UserAccount.OWNER_ID, visited);
            store.saveItems("/articles", List.of(item("New arrival", "https://example.com/new", "Body")));
            int pageCount = 1;
            while (page.nextCursor() != null) {
                assertTrue(pageCount++ < 20, "The cursor must advance");
                page = store.unread().loadUnreadPage(UserAccount.OWNER_ID, null, null, false, page.nextCursor(), 100);
                assertEquals(1_101, page.totalCount());
                visited.addAll(page.items().stream().map(StoredItem::link).toList());
            }

            assertEquals(12, pageCount);
            assertEquals(1_200, visited.size());
            assertEquals(new HashSet<>(originals.stream().map(FeedItem::link).toList()), new HashSet<>(visited));
            assertEquals("https://example.com/article/1199", visited.getFirst());
            assertEquals("https://example.com/article/0", visited.getLast());
            assertFalse(visited.contains("https://example.com/new"));
        }
    }

    @Test
    void combinesSourceChineseQueryAndBookmarksWhileKeepingAllUnreadSourcesAvailable() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            store.saveItems("/a", List.of(
                    item("关于本地模型的记录", "https://example.com/title", "Body"),
                    item("Description match", "https://example.com/description", "采用本地模型推理"),
                    item("Summary match", "https://example.com/summary", "Body"),
                    item("本地模型", "https://example.com/read", "Body"),
                    item("本地模型", "https://example.com/not-bookmarked", "Body"),
                    item("Unrelated", "https://example.com/unrelated", "Body")));
            store.aiCache().saveSummary("https://example.com/summary", "可以使用本地模型总结文章", "test-model");
            store.saveItems("/b", List.of(item("本地模型", "https://example.com/other", "Body")));
            store.saveItems("/c", List.of(item("Other news", "https://example.com/source-c", "Body")));
            store.saveItems("/d", List.of(item("Read source", "https://example.com/source-d", "Body")));
            for (String suffix : List.of("title", "description", "summary", "read", "unrelated", "other")) {
                store.reading().saveBookmark(UserAccount.OWNER_ID, "https://example.com/" + suffix, suffix, "/a");
            }
            store.reading().markAllRead(UserAccount.OWNER_ID, List.of("https://example.com/read", "https://example.com/source-d"));

            UnreadPage first = store.unread().loadUnreadPage(UserAccount.OWNER_ID, " /a ", " 本地模型 ", true, null, 2);

            assertEquals(List.of("https://example.com/summary", "https://example.com/description"), links(first));
            assertEquals(links(first), first.bookmarkedLinks());
            assertEquals(3, first.totalCount());
            assertEquals(List.of("/a", "/b", "/c"), first.sources());
            assertNotNull(first.nextCursor());

            UnreadPage second = store.unread().loadUnreadPage(UserAccount.OWNER_ID, "/a", "本地模型", true, first.nextCursor(), 2);
            assertEquals(List.of("https://example.com/title"), links(second));
            assertEquals(3, second.totalCount());
            assertEquals(links(second), second.bookmarkedLinks());
            assertNull(second.nextCursor());

            UnreadPage noMatches = store.unread().loadUnreadPage(UserAccount.OWNER_ID, "/missing", "本地模型", true, null, 2);
            assertTrue(noMatches.items().isEmpty());
            assertEquals(0, noMatches.totalCount());
            assertEquals(List.of("/a", "/b", "/c"), noMatches.sources());
        }
    }

    @Test
    void treatsQueryWildcardsAndSqlAsLiteralTextAndMatchesAsciiCaseInsensitively() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            store.saveItems("/articles", List.of(
                    item("Literal token", "https://example.com/literal", "Budget 100%_complete"),
                    item("Wildcard lookalike", "https://example.com/wildcard", "Budget 100XXcomplete"),
                    item("HACKER News", "https://example.com/case", "Body")));

            assertEquals(List.of("https://example.com/literal"),
                    links(store.unread().loadUnreadPage(UserAccount.OWNER_ID, null, "%_", false, null, 10)));
            assertEquals(List.of("https://example.com/case"),
                    links(store.unread().loadUnreadPage(UserAccount.OWNER_ID, null, " hacker ", false, null, 10)));
            assertTrue(store.unread().loadUnreadPage(UserAccount.OWNER_ID, null, "' OR 1=1 --", false, null, 10).items().isEmpty());
        }
    }

    @Test
    void excludesInvalidTitlesAndLinksFromItemsCountsAndSources() throws Exception {
        Path database = tempDir.resolve("feed.db");
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.saveItems("/valid", List.of(item("Valid", "https://example.com/valid", "Body")));
            store.saveItems("/invalid", List.of(
                    item(null, "https://example.com/null", "Body"),
                    item("", "https://example.com/empty", "Body"),
                    item(" \t\n\r", "https://example.com/blank", "Body")));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO feed_items (route_path, item_link, title, first_seen_at, last_seen_at)
                        VALUES ('/invalid-link', ' ', 'Missing link', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')
                        """);
            }

            UnreadPage page = store.unread().loadUnreadPage(UserAccount.OWNER_ID, "  ", "\t", false, null, 10);

            assertEquals(List.of("https://example.com/valid"), links(page));
            assertEquals(1, page.totalCount());
            assertEquals(List.of("/valid"), page.sources());
        }
    }

    @Test
    void unreadStateBookmarksAndFirstSourceOwnershipSurviveRestart() throws Exception {
        Path database = tempDir.resolve("feed.db");
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.saveItems("/first", List.of(
                    item("Original", "https://example.com/shared", "Body"),
                    item("Read", "https://example.com/read", "Body")));
            store.saveItems("/second", List.of(item("Updated", "https://example.com/shared", "Updated body")));
            store.reading().saveBookmark(UserAccount.OWNER_ID, "https://example.com/shared", "Updated", "/second");
            store.reading().markRead(UserAccount.OWNER_ID, "https://example.com/read");
        }

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            UnreadPage page = store.unread().loadUnreadPage(UserAccount.OWNER_ID, "/first", null, true, null, 10);
            assertEquals(List.of("https://example.com/shared"), links(page));
            assertEquals(1, page.totalCount());
            assertEquals(links(page), page.bookmarkedLinks());
            assertEquals(List.of("/first"), page.sources());
            assertTrue(store.unread().loadUnreadPage(UserAccount.OWNER_ID, "/second", null, false, null, 10).items().isEmpty());
        }
    }

    @Test
    void rejectsInvalidPageBoundariesAndSupportsAnEmptyDatabase() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            assertThrows(IllegalArgumentException.class, () -> store.unread().loadUnreadPage(UserAccount.OWNER_ID, null, null, false, null, 0));
            assertThrows(IllegalArgumentException.class, () -> store.unread().loadUnreadPage(UserAccount.OWNER_ID, null, null, false, null, 101));
            assertThrows(IllegalArgumentException.class, () -> store.unread().loadUnreadPage(UserAccount.OWNER_ID, null, null, false, 0L, 10));
            assertThrows(IllegalArgumentException.class, () -> store.unread().loadUnreadPage(UserAccount.OWNER_ID, null, null, false, -1L, 10));
            assertEquals(new UnreadPage(List.of(), List.of(), 0, null, List.of()),
                    store.unread().loadUnreadPage(UserAccount.OWNER_ID, null, null, false, null, 1));
        }
    }

    @Test
    void batchReadFailureDoesNotCommitEarlierLinks() throws Exception {
        Path database = tempDir.resolve("feed.db");
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.reading().markRead(UserAccount.OWNER_ID, "https://example.com/existing");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER reject_read BEFORE INSERT ON read_items
                        WHEN NEW.item_link = 'https://example.com/rejected'
                        BEGIN SELECT RAISE(ABORT, 'rejected for test'); END
                        """);
            }

            assertThrows(SQLException.class, () -> store.reading().markAllRead(UserAccount.OWNER_ID, List.of(
                    "https://example.com/first", "https://example.com/rejected", "https://example.com/last")));

            assertEquals(List.of("https://example.com/existing"), store.reading().loadReadLinks(UserAccount.OWNER_ID));
        }
    }

    @Test
    void batchReadSupportsFiveHundredLinksAndRepeatedLinks() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            List<String> links = IntStream.range(0, 500).mapToObj(i -> "https://example.com/" + i).toList();
            store.reading().markAllRead(UserAccount.OWNER_ID, links);
            store.reading().markAllRead(UserAccount.OWNER_ID, List.of(links.getFirst(), links.getFirst()));
            store.reading().markAllRead(UserAccount.OWNER_ID, List.of());

            assertEquals(Set.copyOf(links), Set.copyOf(store.reading().loadReadLinks(UserAccount.OWNER_ID)));
            assertEquals(500, store.reading().loadReadLinks(UserAccount.OWNER_ID).size());
        }
    }

    private static List<String> links(UnreadPage page) {
        return page.items().stream().map(StoredItem::link).toList();
    }

    private static FeedItem item(String title, String link, String description) {
        return new FeedItem(title, link, description, Instant.parse("2020-01-01T00:00:00Z"), null, List.of());
    }
}
