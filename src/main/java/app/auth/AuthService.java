package app.auth;

import app.store.AccountCredentialsStore;
import app.store.FeedStore;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Account credentials, independently revocable browser sessions, and read-only RSS keys. */
public class AuthService {
    public record IssuedSession(UserAccount user, String secret, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedSession[userId=" + user.id() + ", expiresAt=" + expiresAt + "]";
        }
    }

    public static final Duration SESSION_LIFETIME = Duration.ofDays(180);
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);
    private static final Duration CLEANUP_INTERVAL = Duration.ofHours(1);
    private static final int MIN_CONFIGURED_TOKEN_LENGTH = 16;
    private static final int MAX_NAME_LENGTH = 40;

    private final FeedStore store;
    private final boolean enabled;
    private final Clock clock;
    private volatile boolean configuredOwnerToken;
    private Instant nextSessionCleanup = Instant.MIN;

    public AuthService(FeedStore store, boolean enabled) {
        this(store, enabled, Clock.systemUTC());
    }

    AuthService(FeedStore store, boolean enabled, Clock clock) {
        this.store = store;
        this.enabled = enabled;
        this.clock = clock;
    }

    public boolean enabled() {
        return enabled;
    }

    public void bootstrap(String configuredAdminToken) throws SQLException {
        if (!enabled) {
            log.warn("Authentication is DISABLED (fetch-news.auth.enabled=false). "
                    + "Do not expose this instance to the internet.");
            return;
        }
        synchronized (store) {
            configuredOwnerToken = configuredAdminToken != null && !configuredAdminToken.isBlank();
            if (configuredOwnerToken) {
                String token = configuredAdminToken.strip();
                if (token.length() < MIN_CONFIGURED_TOKEN_LENGTH || !token.matches("[A-Za-z0-9._~+/=-]+")) {
                    throw new IllegalStateException("FETCH_NEWS_ADMIN_TOKEN must be at least "
                            + MIN_CONFIGURED_TOKEN_LENGTH + " characters of A-Z a-z 0-9 . _ ~ + / = -");
                }
                store.accounts().updateUserTokenHash(UserAccount.OWNER_ID, AuthTokens.hash(token));
                log.info("Admin token loaded from FETCH_NEWS_ADMIN_TOKEN");
                return;
            }
            UserAccount owner = require(UserAccount.OWNER_ID);
            if (owner.hasToken() || owner.hasPassword()) return;
            String token = AuthTokens.newToken();
            store.accounts().updateUserTokenHash(UserAccount.OWNER_ID, AuthTokens.hash(token));
            log.warn("""

                    ================================================================
                      fetch-news admin token (shown only once — save it now):

                        {}

                      Sign in at /login. Lost it? Restart with FETCH_NEWS_ADMIN_TOKEN
                      set to a new secret of at least 16 characters.
                    ================================================================""", token);
        }
    }

    public UserAccount authenticateToken(String token) throws SQLException {
        if (token == null || token.isBlank()) return null;
        return touch(store.accounts().findUserByTokenHash(AuthTokens.hash(token.strip())));
    }

    public UserAccount authenticateFeedKey(String feedKey) throws SQLException {
        if (feedKey == null || feedKey.isBlank()) return null;
        return touch(store.accounts().findUserByFeedKey(feedKey.strip()));
    }

    public IssuedSession loginWithToken(String token) throws SQLException {
        return issueTokenSession(token, false);
    }

    public IssuedSession exchangeLegacyCookie(String rawToken) throws SQLException {
        return issueTokenSession(rawToken, true);
    }

    private IssuedSession issueTokenSession(String token, boolean primaryOnly) throws SQLException {
        if (token == null || token.isBlank()) return null;
        String secret = AuthTokens.newSession();
        Instant expires = clock.instant().plus(SESSION_LIFETIME);
        UserAccount user = store.accounts().sessions().issueForToken(
                AuthTokens.hash(token.strip()), primaryOnly, AuthTokens.hash(secret), expires);
        return issued(user, secret, expires);
    }

    public IssuedSession loginWithPassword(String name, String password) throws SQLException {
        String normalized = name == null ? "" : name.strip();
        AccountCredentialsStore.Password account = store.accounts().credentials().passwordByName(normalized);
        if (!Passwords.matches(password, account == null ? null : account.hash())) return null;
        String secret = AuthTokens.newSession();
        Instant expires = clock.instant().plus(SESSION_LIFETIME);
        UserAccount user = store.accounts().sessions().issueForPassword(
                account.userId(), account.hash(), AuthTokens.hash(secret), expires);
        return issued(user, secret, expires);
    }

    private IssuedSession issued(UserAccount user, String secret, Instant expires) throws SQLException {
        if (user == null) return null;
        expireOldSessions();
        return new IssuedSession(touch(user), secret, expires);
    }

    private void expireOldSessions() throws SQLException {
        synchronized (store) {
            Instant now = clock.instant();
            if (now.isBefore(nextSessionCleanup)) return;
            store.accounts().sessions().expire(now);
            nextSessionCleanup = now.plus(CLEANUP_INTERVAL);
        }
    }

    public UserAccount authenticateSession(String secret) throws SQLException {
        if (secret == null || !secret.startsWith("fs_")) return null;
        expireOldSessions();
        return touch(store.accounts().findUserBySession(AuthTokens.hash(secret), clock.instant()));
    }

    public void logout(String secret) throws SQLException {
        if (secret != null && secret.startsWith("fs_")) store.accounts().sessions().remove(AuthTokens.hash(secret));
    }

    private UserAccount touch(UserAccount user) throws SQLException {
        if (user == null) return null;
        Instant now = clock.instant();
        if (user.lastSeenAt() == null || user.lastSeenAt().isBefore(now.minus(TOUCH_INTERVAL))) {
            store.accounts().touchUser(user.id(), now);
        }
        return user;
    }

    public List<UserAccount> listUsers() throws SQLException {
        return store.accounts().listUsers();
    }

    public UserAccount findUser(long id) throws SQLException {
        return store.accounts().findUser(id);
    }

    public IssuedToken createUser(String name, Role role) throws SQLException {
        String token = AuthTokens.newToken();
        UserAccount user = store.accounts().createUser(validName(name), role,
                AuthTokens.hash(token), AuthTokens.newFeedKey());
        return new IssuedToken(user, token);
    }

    public UserAccount createUser(String name, String password) throws SQLException {
        return store.accounts().createAccount(validName(name), null, Passwords.hash(password), AuthTokens.newFeedKey());
    }

    public List<AccountToken> listTokens(long userId) throws SQLException {
        return store.accounts().credentials().listTokens(userId, protectsPrimary(userId));
    }

    public IssuedToken createToken(long userId, String name) throws SQLException {
        String token = AuthTokens.newToken();
        UserAccount user = store.accounts().credentials().addToken(userId, validName(name), AuthTokens.hash(token));
        return new IssuedToken(user, token);
    }

    public void revokeToken(long userId, long tokenId) throws SQLException {
        synchronized (store) {
            store.accounts().credentials().revokeToken(userId, tokenId, protectsPrimary(userId));
        }
    }

    public IssuedToken resetToken(long id) throws SQLException {
        synchronized (store) {
            require(id);
            if (protectsPrimary(id)) throw new IllegalArgumentException("此主令牌由 FETCH_NEWS_ADMIN_TOKEN 管理。");
            String token = AuthTokens.newToken();
            store.accounts().updateUserTokenHash(id, AuthTokens.hash(token));
            return new IssuedToken(require(id), token);
        }
    }

    private boolean protectsPrimary(long userId) {
        return userId == UserAccount.OWNER_ID && configuredOwnerToken;
    }

    public void changePassword(long userId, String oldPassword, String newPassword) throws SQLException {
        var credentials = store.accounts().credentials();
        var current = credentials.passwordById(userId);
        if (current == null) throw new IllegalArgumentException("账户不存在。");
        if (current.hash() != null && !Passwords.matches(oldPassword, current.hash())) {
            throw new IllegalArgumentException("当前密码不正确。");
        }
        credentials.setPassword(userId, current.hash(), Passwords.hash(newPassword), false);
    }

    public void resetPassword(long actingUserId, long targetId, String newPassword) throws SQLException {
        requireOwner(actingUserId);
        if (targetId == UserAccount.OWNER_ID) throw new IllegalArgumentException("请在个人账户页修改管理员密码。");
        require(targetId);
        store.accounts().credentials().setPassword(targetId, null, Passwords.hash(newPassword), true);
    }

    public UserAccount resetFeedKey(long id) throws SQLException {
        require(id);
        store.accounts().updateUserFeedKey(id, AuthTokens.newFeedKey());
        return require(id);
    }

    public void setDisabled(long actingUserId, long id, boolean disabled) throws SQLException {
        requireOwner(actingUserId);
        require(id);
        if (disabled && actingUserId == id) throw new IllegalArgumentException("不能停用当前登录的账户。");
        store.accounts().setUserDisabled(id, disabled);
    }

    public void delete(long actingUserId, long id) throws SQLException {
        requireOwner(actingUserId);
        require(id);
        store.accounts().deleteUser(id);
    }

    private void requireOwner(long userId) {
        if (userId != UserAccount.OWNER_ID) throw new IllegalArgumentException("只有初始管理员可以管理成员。");
    }

    private String validName(String name) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("名称需为 1–" + MAX_NAME_LENGTH + " 个字符。");
        }
        return normalized;
    }

    private UserAccount require(long id) throws SQLException {
        UserAccount user = store.accounts().findUser(id);
        if (user == null) throw new IllegalArgumentException("账户不存在。");
        return user;
    }
}
