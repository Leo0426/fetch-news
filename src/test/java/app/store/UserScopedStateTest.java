package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.auth.AuthTokens;
import app.auth.Role;
import app.auth.UserAccount;
import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserScopedStateTest {
    @TempDir
    Path tempDir;

    @Test
    void singleUserBookmarksAndReadStateMigrateToTheOwner() throws Exception {
        Path database = tempDir.resolve("legacy.db");
        String link = "https://example.com/legacy";
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.saveItems("/legacy", List.of(item("Legacy", link)));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE bookmarks");
            statement.execute("DROP TABLE read_items");
            statement.execute("CREATE TABLE bookmarks (item_link TEXT PRIMARY KEY, title TEXT, route_path TEXT,"
                    + " bookmarked_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE read_items (item_link TEXT PRIMARY KEY, read_at TEXT NOT NULL)");
            statement.execute("INSERT INTO bookmarks VALUES ('" + link + "', 'Legacy', '/legacy',"
                    + " '2026-01-01T00:00:00Z')");
            statement.execute("INSERT INTO read_items VALUES ('" + link + "', '2026-01-01T00:00:00Z')");
        }

        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertTrue(store.reading().isBookmarked(UserAccount.OWNER_ID, link));
            assertTrue(store.reading().isRead(UserAccount.OWNER_ID, link));
            assertEquals("Legacy", store.reading().loadBookmarks(UserAccount.OWNER_ID).getFirst().title());
            UserAccount member = store.accounts().createUser("reader", Role.MEMBER, AuthTokens.hash("t"), AuthTokens.newFeedKey());
            assertFalse(store.reading().isBookmarked(member.id(), link));
            assertFalse(store.reading().isRead(member.id(), link));
        }
    }

    @Test
    void bookmarksReadStateAndUnreadInboxAreIndependentPerUser() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("users.db"), new ObjectMapper())) {
            String first = "https://example.com/one";
            String second = "https://example.com/two";
            store.saveItems("/news", List.of(item("One", first), item("Two", second)));
            UserAccount alice = store.accounts().createUser("alice", Role.MEMBER, AuthTokens.hash("a"), AuthTokens.newFeedKey());
            UserAccount bob = store.accounts().createUser("bob", Role.MEMBER, AuthTokens.hash("b"), AuthTokens.newFeedKey());

            store.reading().markAllRead(alice.id(), List.of(first));
            store.reading().saveBookmark(bob.id(), second, "Two", "/news");

            assertEquals(List.of(second), links(store.unread().loadUnreadPage(alice.id(), null, null, false, null, 10)));
            assertEquals(2, store.unread().loadUnreadPage(bob.id(), null, null, false, null, 10).totalCount());
            assertEquals(List.of(second), links(store.unread().loadUnreadPage(bob.id(), null, null, true, null, 10)));
            assertTrue(store.unread().loadUnreadPage(alice.id(), null, null, true, null, 10).items().isEmpty());
            assertTrue(store.loadArticle(alice.id(), first).read());
            assertFalse(store.loadArticle(bob.id(), first).read());
            assertTrue(store.loadArticle(bob.id(), second).bookmarked());
            assertFalse(store.loadArticle(alice.id(), second).bookmarked());
            assertEquals(List.of(), store.reading().loadBookmarkLinks(alice.id()));
            assertEquals(List.of(), store.reading().loadReadLinks(UserAccount.OWNER_ID));

            store.accounts().deleteUser(bob.id());
            assertNull(store.accounts().findUser(bob.id()));
            assertEquals(List.of(), store.reading().loadBookmarkLinks(bob.id()));
            assertEquals(List.of(first), store.reading().loadReadLinks(alice.id()));
        }
    }

    @Test
    void disabledAccountsCannotBeFoundBySecrets() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("disabled.db"), new ObjectMapper())) {
            String hash = AuthTokens.hash("secret-token");
            UserAccount user = store.accounts().createUser("carol", Role.MEMBER, hash, "fk_carol");
            assertEquals(user.id(), store.accounts().findUserByTokenHash(hash).id());
            assertEquals(user.id(), store.accounts().findUserByFeedKey("fk_carol").id());
            store.accounts().setUserDisabled(user.id(), true);
            assertNull(store.accounts().findUserByTokenHash(hash));
            assertNull(store.accounts().findUserByFeedKey("fk_carol"));
        }
    }

    private static List<String> links(UnreadPage page) {
        return page.items().stream().map(StoredItem::link).toList();
    }

    private static FeedItem item(String title, String link) {
        return new FeedItem(title, link, "Body", Instant.parse("2026-01-01T00:00:00Z"), null, List.of());
    }
}
