package app.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountCredentialMigrationTest {
    @TempDir
    Path tempDir;

    @Test
    void existingAdminsBecomeMembersWithTheirOriginalSecretsAndReadingState() throws Exception {
        Path db = tempDir.resolve("upgrade.db");
        String memberSecret = "legacy-member-login-secret";
        String link = "https://example.com/legacy";
        String memberFeed = "fk_legacy_member";
        var mapper = new ObjectMapper();
        try (var store = new FeedStore(db, mapper)) {
            store.saveItems("/news", List.of(new FeedItem("article", link, "Body", Instant.now(), null, List.of())));
            store.reading().markAllRead(7, List.of(link));
            store.reading().saveBookmark(7, link, "article", "/news");
        }
        try (var dbConn = DriverManager.getConnection("jdbc:sqlite:" + db); var stmt = dbConn.createStatement()) {
            stmt.execute("DROP TABLE account_sessions");
            stmt.execute("DROP TABLE account_tokens");
            stmt.execute("DROP TABLE users");
            stmt.execute("""
                    CREATE TABLE users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        role TEXT NOT NULL, token_hash TEXT UNIQUE, feed_key TEXT NOT NULL UNIQUE,
                        created_at TEXT NOT NULL, last_seen_at TEXT, disabled INTEGER NOT NULL DEFAULT 0
                    )""");
            try (var insert = dbConn.prepareStatement("""
                    INSERT INTO users(id,name,role,token_hash,feed_key,created_at,disabled)
                    VALUES (?,?,'ADMIN',?,?,'2026-01-01T00:00:00Z',?)""")) {
                insert.setLong(1, 1);
                insert.setString(2, "existing-owner");
                insert.setString(3, AuthTokens.hash("legacy-owner-token"));
                insert.setString(4, "fk_legacy_owner");
                insert.setInt(5, 1);
                insert.executeUpdate();
                insert.setLong(1, 7);
                insert.setString(2, "legacy editor");
                insert.setString(3, AuthTokens.hash(memberSecret));
                insert.setString(4, memberFeed);
                insert.setInt(5, 0);
                insert.executeUpdate();
            }
        }
        long oldToken;
        try (var store = new FeedStore(db, mapper)) {
            var auth = new AuthService(store, true);
            assertEquals(1, auth.listUsers().stream().filter(UserAccount::admin).count());
            assertEquals("existing-owner", auth.authenticateToken("legacy-owner-token").name());
            assertEquals(Role.MEMBER, auth.authenticateToken(memberSecret).role());
            assertEquals(7, auth.authenticateFeedKey(memberFeed).id());
            assertEquals(7, auth.exchangeLegacyCookie(memberSecret).user().id());
            assertTrue(store.reading().isBookmarked(7, link));
            assertTrue(store.reading().isRead(7, link));
            var primary = auth.listTokens(7).getFirst();
            assertTrue(primary.primary());
            assertFalse(primary.protectedByEnvironment());
            oldToken = primary.id();
            auth.changePassword(7, null, "member can now use a password");
            auth.revokeToken(7, oldToken);
        }
        try (var store = new FeedStore(db, mapper)) {
            var auth = new AuthService(store, true);
            assertNull(auth.exchangeLegacyCookie(memberSecret));
            assertNull(auth.authenticateToken(memberSecret));
            assertFalse(auth.findUser(7).hasToken());
            assertEquals(7, auth.loginWithPassword("legacy editor", "member can now use a password").user().id());
            assertTrue(store.reading().isRead(7, link));
            assertEquals(memberFeed, auth.findUser(7).feedKey());
        }
    }

    @Test
    void passwordsAreIndependentlySaltedAndRandomSecretsAreHashedAtRest() throws Exception {
        Path db = tempDir.resolve("secrets.db");
        String password = "same password for comparison";
        try (var store = new FeedStore(db, new ObjectMapper());
             var dbConn = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            var auth = new AuthService(store, true);
            var alice = auth.createUser("Alice", password);
            var bob = auth.createUser("Bob", password);
            var aliceToken = auth.createToken(alice.id(), "work");
            var session = auth.loginWithPassword("Alice", password);
            assertNotNull(session);
            try (var stmt = dbConn.createStatement();
                 var rows = stmt.executeQuery(
                         "SELECT password_hash FROM users WHERE password_hash IS NOT NULL ORDER BY id")) {
                assertTrue(rows.next());
                String first = rows.getString(1);
                assertTrue(rows.next());
                String second = rows.getString(1);
                assertNotEquals(first, second);
                assertFalse(first.contains(password) || second.contains(password));
            }
            try (var stmt = dbConn.createStatement();
                 var row = stmt.executeQuery("SELECT token_hash, session_hash FROM account_tokens, account_sessions")) {
                assertTrue(row.next());
                assertEquals(AuthTokens.hash(aliceToken.token()), row.getString(1));
                assertEquals(AuthTokens.hash(session.secret()), row.getString(2));
            }
            assertFalse(session.toString().contains(session.secret()));
            assertFalse(aliceToken.toString().contains(aliceToken.token()));
            assertFalse(bob.toString().contains(bob.feedKey()));
            assertThrows(IllegalArgumentException.class, () -> auth.createUser("Alice", password));
            assertThrows(IllegalArgumentException.class, () -> auth.createUser("weak", "short"));
            assertEquals(3, auth.listUsers().size());
        }
    }

    @Test
    void failedInitialTokenInsertRollsBackTheWholeNewUser() throws Exception {
        Path db = tempDir.resolve("rollback.db");
        try (var store = new FeedStore(db, new ObjectMapper());
             var dbConn = DriverManager.getConnection("jdbc:sqlite:" + db); var stmt = dbConn.createStatement()) {
            var auth = new AuthService(store, true);
            stmt.execute("CREATE TRIGGER reject_token BEFORE INSERT ON account_tokens"
                    + " BEGIN SELECT RAISE(ABORT,'stop'); END");
            assertThrows(SQLException.class, () -> auth.createUser("member", Role.MEMBER));
            assertEquals(1, auth.listUsers().size());
        }
    }
}
