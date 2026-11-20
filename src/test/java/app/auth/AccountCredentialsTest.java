package app.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.store.FeedStore;
import app.store.PickKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountCredentialsTest {
    @TempDir
    Path tempDir;

    @Test
    void onlyTheInitialOwnerCanHoldTheAdminRole() throws Exception {
        try (var store = new FeedStore(tempDir.resolve("owner.db"), new ObjectMapper())) {
            var auth = new AuthService(store, true);
            assertThrows(IllegalArgumentException.class, () -> auth.createUser("second admin", Role.ADMIN));
        }
    }

    @Test
    void revokingOneNamedTokenAlsoRevokesItsSessionsOnly() throws Exception {
        try (var store = new FeedStore(tempDir.resolve("tokens.db"), new ObjectMapper())) {
            var auth = new AuthService(store, true);
            var primary = auth.createUser("member", Role.MEMBER);
            var device = auth.createToken(primary.user().id(), "手机");
            var first = auth.loginWithToken(primary.token());
            var second = auth.loginWithToken(device.token());
            assertNotEquals(first.secret(), second.secret());
            assertNull(auth.exchangeLegacyCookie(device.token()));
            assertNull(auth.exchangeLegacyCookie(primary.token()));
            long tokenId = auth.listTokens(primary.user().id()).stream()
                    .filter(t -> t.name().equals("手机")).findFirst().orElseThrow().id();
            auth.revokeToken(primary.user().id(), tokenId);
            assertNull(auth.authenticateSession(second.secret()));
            assertNull(auth.authenticateToken(device.token()));
            assertEquals(primary.user().id(), auth.authenticateSession(first.secret()).id());
        }
    }

    @Test
    void disablingAnAccountRetiresItsRawCookieEvenAfterItIsEnabledAgain() throws Exception {
        String legacyToken = "member-legacy-credential";
        try (var store = migratedMember("disable.db", legacyToken)) {
            var auth = new AuthService(store, true);
            long id = auth.authenticateToken(legacyToken).id();
            var migrated = auth.exchangeLegacyCookie(legacyToken);
            assertNotNull(migrated);
            auth.setDisabled(UserAccount.OWNER_ID, id, true);
            auth.setDisabled(UserAccount.OWNER_ID, id, false);
            assertNull(auth.authenticateSession(migrated.secret()));
            assertNull(auth.exchangeLegacyCookie(legacyToken));
            assertNotNull(auth.loginWithToken(legacyToken));
        }
    }

    @Test
    void ownerRecoveryPinsOnlyTheEnvironmentTokenAndRotationRevokesItsSessions() throws Exception {
        try (var store = new FeedStore(tempDir.resolve("env.db"), new ObjectMapper())) {
            var auth = new AuthService(store, true);
            long owner = UserAccount.OWNER_ID;
            String old = "owner-recovery-credential-1";
            String updated = "owner-recovery-credential-2";
            auth.bootstrap(old);
            var original = auth.loginWithToken(old);
            var phone = auth.createToken(owner, "Phone");
            var mobile = auth.loginWithToken(phone.token());
            AccountToken primary = auth.listTokens(owner).stream()
                    .filter(AccountToken::primary).findFirst().orElseThrow();
            assertTrue(primary.protectedByEnvironment());
            assertThrows(IllegalArgumentException.class, () -> auth.revokeToken(owner, primary.id()));
            assertThrows(IllegalArgumentException.class, () -> auth.resetToken(owner));
            auth.bootstrap(old);
            assertNotNull(auth.authenticateSession(original.secret()));
            auth.bootstrap(updated);
            assertNull(auth.authenticateSession(original.secret()));
            assertNull(auth.authenticateToken(old));
            assertNotNull(auth.authenticateSession(mobile.secret()));
            assertEquals(owner, auth.loginWithToken(updated).user().id());
        }
    }

    @Test
    void ownerCanManageThePrimaryButKeepsAtLeastOneLoginMethod() throws Exception {
        try (var store = new FeedStore(tempDir.resolve("last.db"), new ObjectMapper())) {
            var auth = new AuthService(store, true);
            long owner = UserAccount.OWNER_ID;
            var initial = auth.resetToken(owner);
            long primaryId = auth.listTokens(owner).getFirst().id();
            assertThrows(IllegalArgumentException.class, () -> auth.revokeToken(owner, primaryId));
            auth.changePassword(owner, null, "owner password");
            auth.revokeToken(owner, primaryId);
            auth.bootstrap(null);
            assertFalse(auth.findUser(owner).hasToken());
            assertNull(auth.authenticateToken(initial.token()));
            assertNotNull(auth.loginWithPassword("admin", "owner password"));
        }
    }

    @Test
    void membersCanRevokeAnyTokenAsLongAsTheyKeepOneCredential() throws Exception {
        try (var store = new FeedStore(tempDir.resolve("member-last.db"), new ObjectMapper())) {
            var auth = new AuthService(store, true);
            var initial = auth.createUser("member", Role.MEMBER);
            long id = initial.user().id();
            long primaryId = auth.listTokens(id).getFirst().id();
            assertThrows(IllegalArgumentException.class, () -> auth.revokeToken(id, primaryId));
            var extra = auth.createToken(id, "phone");
            auth.revokeToken(id, primaryId);
            long extraId = auth.listTokens(id).getFirst().id();
            assertThrows(IllegalArgumentException.class, () -> auth.revokeToken(id, extraId));
            auth.changePassword(id, null, "members own password");
            auth.revokeToken(id, extraId);
            assertNull(auth.authenticateToken(extra.token()));
            assertNotNull(auth.loginWithPassword("member", "members own password"));
        }
    }

    @Test
    void independentPasswordSessionsExpireOnTheServerAndCanBeLoggedOutSingly() throws Exception {
        var start = Instant.parse("2026-01-01T00:00:00Z");
        try (var store = new FeedStore(tempDir.resolve("expiry.db"), new ObjectMapper())) {
            var auth = new AuthService(store, true, Clock.fixed(start, ZoneOffset.UTC));
            String password = " password also has spaces ";
            var account = auth.createUser("MixedCase", password);
            assertTrue(account.hasPassword());
            assertFalse(account.hasToken());
            assertNull(auth.loginWithPassword("MixedCase", password.strip()));
            var first = auth.loginWithPassword("mixedcase", password);
            var second = auth.loginWithPassword("MixedCase", password);
            assertNotEquals(first.secret(), second.secret());
            assertNull(auth.authenticateToken(first.secret()));
            assertNull(auth.authenticateFeedKey(second.secret()));
            auth.logout(first.secret());
            assertNull(auth.authenticateSession(first.secret()));
            assertNotNull(auth.authenticateSession(second.secret()));
            var future = new AuthService(store, true, Clock.fixed(start.plusSeconds(180 * 86400L), ZoneOffset.UTC));
            assertEquals(start.plusSeconds(180 * 86400L), second.expiresAt());
            assertNull(future.authenticateSession(second.secret()));
        }
    }

    @Test
    void passwordChangesRequireTheCurrentSecretAndRetireBrowserSessionsOnly() throws Exception {
        String legacyToken = "member-legacy-credential";
        try (var store = migratedMember("password.db", legacyToken)) {
            var auth = new AuthService(store, true);
            long id = auth.authenticateToken(legacyToken).id();
            String first = "old password";
            String changed = "changed password";
            String reset = "reset password";
            var oldSession = auth.exchangeLegacyCookie(legacyToken);
            auth.changePassword(id, null, first);
            assertNull(auth.authenticateSession(oldSession.secret()));
            assertNull(auth.exchangeLegacyCookie(legacyToken));
            assertThrows(IllegalArgumentException.class, () -> auth.changePassword(id, "wrong", changed));
            var beforeChange = auth.loginWithToken(legacyToken);
            var alsoBeforeChange = auth.loginWithPassword("legacy", first);
            auth.changePassword(id, first, changed);
            assertNull(auth.authenticateSession(beforeChange.secret()));
            assertNull(auth.authenticateSession(alsoBeforeChange.secret()));
            assertNull(auth.loginWithPassword("legacy", first));
            var beforeReset = auth.loginWithPassword("legacy", changed);
            assertThrows(IllegalArgumentException.class, () -> auth.resetPassword(id, id, reset));
            assertThrows(IllegalArgumentException.class, () -> auth.resetPassword(1, 1, reset));
            auth.resetPassword(1, id, reset);
            assertNull(auth.authenticateSession(beforeReset.secret()));
            assertNotNull(auth.loginWithPassword("legacy", reset));
            assertEquals(id, auth.authenticateToken(legacyToken).id());
        }
    }

    @Test
    void competingInitialPasswordChangesCannotBothSucceed() throws Exception {
        try (var store = new FeedStore(tempDir.resolve("race.db"), new ObjectMapper());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var auth = new AuthService(store, true);
            long userId = auth.createUser("racing", Role.MEMBER).user().id();
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> setPassword(auth, userId, "first credentials", start));
            var second = executor.submit(() -> setPassword(auth, userId, "second credentials", start));
            start.countDown();
            assertNotEquals(first.get(), second.get());
            assertEquals(first.get(), auth.loginWithPassword("racing", "first credentials") != null);
            assertEquals(second.get(), auth.loginWithPassword("racing", "second credentials") != null);
        }
    }

    private FeedStore migratedMember(String file, String legacyToken) throws Exception {
        Path db = tempDir.resolve(file);
        try (var seed = new FeedStore(db, new ObjectMapper());
             var dbConn = DriverManager.getConnection("jdbc:sqlite:" + db);
             var stmt = dbConn.prepareStatement("""
                     INSERT INTO users(name,role,token_hash,feed_key,created_at)
                     VALUES ('legacy','MEMBER',?,'fk_legacy','2026-01-01T00:00:00Z')""")) {
            stmt.setString(1, AuthTokens.hash(legacyToken));
            stmt.executeUpdate();
        }
        return new FeedStore(db, new ObjectMapper());
    }

    private boolean setPassword(AuthService auth, long userId, String password, CountDownLatch start) throws Exception {
        start.await();
        try {
            auth.changePassword(userId, null, password);
            return true;
        } catch (IllegalArgumentException lostRace) {
            return false;
        }
    }

    @Test
    void deletingAMemberForgetsCredentialsSessionsAndFeedbackAtomically() throws Exception {
        Path db = tempDir.resolve("delete.db");
        try (var store = new FeedStore(db, new ObjectMapper())) {
            var auth = new AuthService(store, true);
            var original = auth.createUser("member", Role.MEMBER);
            long member = original.user().id();
            var extra = auth.createToken(member, "phone");
            var session = auth.loginWithToken(extra.token());
            String link = "https://example.com/item";
            store.picks().setFeedback(member, link, PickKind.QUALITY, "up", Instant.now());
            store.picks().setFeedback(1, link, PickKind.QUALITY, "down", Instant.now());
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db); var stmt = conn.createStatement()) {
                stmt.execute("CREATE TRIGGER fail_delete BEFORE DELETE ON users"
                        + " BEGIN SELECT RAISE(ABORT, 'test'); END");
                assertThrows(SQLException.class, () -> auth.delete(1, member));
                assertEquals(member, auth.authenticateSession(session.secret()).id());
                assertFalse(store.picks().feedback(member, List.of(link)).isEmpty());
                stmt.execute("DROP TRIGGER fail_delete");
            }
            auth.delete(1, member);
            assertNull(auth.authenticateSession(session.secret()));
            assertNull(auth.authenticateToken(original.token()));
            assertNull(auth.authenticateToken(extra.token()));
            assertNull(auth.authenticateFeedKey(original.user().feedKey()));
            assertTrue(auth.listTokens(member).isEmpty());
            assertTrue(store.picks().feedback(member, List.of(link)).isEmpty());
            assertFalse(store.picks().feedback(1, List.of(link)).isEmpty());
        }
    }
}
