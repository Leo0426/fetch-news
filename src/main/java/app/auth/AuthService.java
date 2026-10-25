package app.auth;

import app.store.FeedStore;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issues, verifies and revokes access tokens.
 *
 * <p>Each account has two secrets: a login token (hashed at rest, shown once) used by the
 * browser and API clients, and a feed key embedded in RSS reader URLs that only grants
 * read access to feed output.
 */
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);
    private static final int MIN_CONFIGURED_TOKEN_LENGTH = 16;
    private static final int MAX_NAME_LENGTH = 40;

    private final FeedStore store;
    private final boolean enabled;

    public AuthService(FeedStore store, boolean enabled) {
        this.store = store;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Ensures the owner can sign in. A configured admin token always replaces the stored one,
     * which doubles as the recovery path for a lost token. Otherwise a token is generated once
     * and printed to the log.
     */
    public void bootstrap(String configuredAdminToken) throws SQLException {
        if (!enabled) {
            log.warn("Authentication is DISABLED (fetch-news.auth.enabled=false). "
                    + "Do not expose this instance to the internet.");
            return;
        }
        if (configuredAdminToken != null && !configuredAdminToken.isBlank()) {
            String token = configuredAdminToken.strip();
            // The token is stored in a cookie, so it must be cookie-safe.
            if (token.length() < MIN_CONFIGURED_TOKEN_LENGTH || !token.matches("[A-Za-z0-9._~+/=-]+")) {
                throw new IllegalStateException("FETCH_NEWS_ADMIN_TOKEN must be at least "
                        + MIN_CONFIGURED_TOKEN_LENGTH + " characters of A-Z a-z 0-9 . _ ~ + / = -");
            }
            store.accounts().updateUserTokenHash(UserAccount.OWNER_ID, AuthTokens.hash(token));
            store.accounts().setUserDisabled(UserAccount.OWNER_ID, false);
            log.info("Admin token loaded from FETCH_NEWS_ADMIN_TOKEN");
            return;
        }
        UserAccount owner = store.accounts().findUser(UserAccount.OWNER_ID);
        if (owner != null && owner.hasToken()) return;
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

    /** Returns the enabled account for a login token, or null. */
    public UserAccount authenticateToken(String token) throws SQLException {
        if (token == null || token.isBlank()) return null;
        return touch(store.accounts().findUserByTokenHash(AuthTokens.hash(token.strip())));
    }

    /** Returns the enabled account for a feed key, or null. */
    public UserAccount authenticateFeedKey(String feedKey) throws SQLException {
        if (feedKey == null || feedKey.isBlank()) return null;
        return touch(store.accounts().findUserByFeedKey(feedKey.strip()));
    }

    private UserAccount touch(UserAccount user) throws SQLException {
        if (user == null) return null;
        Instant now = Instant.now();
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
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("名称需为 1–" + MAX_NAME_LENGTH + " 个字符。");
        }
        if (store.accounts().listUsers().stream().anyMatch(u -> u.name().equalsIgnoreCase(normalized))) {
            throw new IllegalArgumentException("名称“" + normalized + "”已被使用。");
        }
        String token = AuthTokens.newToken();
        UserAccount user = store.accounts().createUser(normalized, role, AuthTokens.hash(token), AuthTokens.newFeedKey());
        return new IssuedToken(user, token);
    }

    /** Replaces an account's login token; existing browser sessions of that account end. */
    public IssuedToken resetToken(long id) throws SQLException {
        UserAccount user = require(id);
        String token = AuthTokens.newToken();
        store.accounts().updateUserTokenHash(id, AuthTokens.hash(token));
        return new IssuedToken(user, token);
    }

    /** Replaces an account's feed key; RSS readers using the old URLs stop working. */
    public UserAccount resetFeedKey(long id) throws SQLException {
        require(id);
        store.accounts().updateUserFeedKey(id, AuthTokens.newFeedKey());
        return store.accounts().findUser(id);
    }

    public void setDisabled(long actingUserId, long id, boolean disabled) throws SQLException {
        UserAccount user = require(id);
        if (disabled) guardLastAdmin(actingUserId, user, "停用");
        store.accounts().setUserDisabled(id, disabled);
    }

    public void delete(long actingUserId, long id) throws SQLException {
        UserAccount user = require(id);
        if (id == UserAccount.OWNER_ID) {
            throw new IllegalArgumentException("初始管理员账户不能删除，可以重置其令牌。");
        }
        guardLastAdmin(actingUserId, user, "删除");
        store.accounts().deleteUser(id);
    }

    private void guardLastAdmin(long actingUserId, UserAccount target, String action) throws SQLException {
        if (target.id() == actingUserId) {
            throw new IllegalArgumentException("不能" + action + "当前登录的账户。");
        }
        if (target.admin() && !target.disabled()) {
            long activeAdmins = store.accounts().listUsers().stream().filter(u -> u.admin() && !u.disabled()).count();
            if (activeAdmins <= 1) throw new IllegalArgumentException("至少需要保留一个可用的管理员。");
        }
    }

    private UserAccount require(long id) throws SQLException {
        UserAccount user = store.accounts().findUser(id);
        if (user == null) throw new IllegalArgumentException("账户不存在。");
        return user;
    }
}
