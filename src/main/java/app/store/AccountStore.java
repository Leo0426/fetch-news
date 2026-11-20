package app.store;

import app.auth.AuthTokens;
import app.auth.Role;
import app.auth.UserAccount;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Accounts and their credentials: token hashes, feed keys, roles. Plain tokens never reach this module.
 */
public final class AccountStore {
    private static final String USER_COLUMNS =
            """
                    id, name, role, feed_key, created_at, last_seen_at, disabled,
                    EXISTS (SELECT 1 FROM account_tokens WHERE user_id = users.id) AS has_token,
                    password_hash IS NOT NULL AS has_password
                    """;

    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private final FeedStore store;
    private final AccountCredentialsStore credentials;
    private final AccountSessionStore sessions;

    public AccountSessionStore sessions() {
        return sessions;
    }

    public AccountCredentialsStore credentials() {
        return credentials;
    }

    AccountStore(FeedStore store) {
        this.store = store;
        credentials = new AccountCredentialsStore(store);
        sessions = new AccountSessionStore(store);
    }

    static void createSchema(Statement s) throws SQLException {
        s.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    name         TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    role         TEXT NOT NULL,
                    token_hash   TEXT UNIQUE,
                    feed_key     TEXT NOT NULL UNIQUE,
                    created_at   TEXT NOT NULL,
                    last_seen_at TEXT,
                    disabled     INTEGER NOT NULL DEFAULT 0
                )""");
        try (PreparedStatement ps = s.getConnection().prepareStatement("""
                INSERT OR IGNORE INTO users (id, name, role, feed_key, created_at)
                VALUES (?, 'admin', 'ADMIN', ?, ?)""")) {
            ps.setLong(1, UserAccount.OWNER_ID);
            ps.setString(2, AuthTokens.newFeedKey());
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        }
        AccountCredentialSchema.migrate(s);
    }

    public List<UserAccount> listUsers() throws SQLException {
        return store.read(conn -> {
            List<UserAccount> result = new ArrayList<>();
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT " + USER_COLUMNS + " FROM users ORDER BY id")) {
                while (rs.next()) result.add(readUser(rs));
            }
            return result;
        });
    }

    public UserAccount findUser(long id) throws SQLException {
        return findUserBy("id = ?", ps -> ps.setLong(1, id));
    }

    /** Finds the enabled account holding this login-token hash. */
    public UserAccount findUserByTokenHash(String tokenHash) throws SQLException {
        return findUserBy("id = (SELECT user_id FROM account_tokens WHERE token_hash = ?) AND disabled = 0",
                ps -> ps.setString(1, tokenHash));
    }

    /** Finds the enabled account owning this feed key. */
    public UserAccount findUserByFeedKey(String feedKey) throws SQLException {
        return findUserBy("feed_key = ? AND disabled = 0", ps -> ps.setString(1, feedKey));
    }

    public UserAccount findUserBySession(String hash, Instant now) throws SQLException {
        return findUserBy("disabled = 0 AND id = (SELECT user_id FROM account_sessions"
                + " WHERE session_hash = ? AND expires_at > ?)", ps -> {
            ps.setString(1, hash);
            ps.setLong(2, now.getEpochSecond());
        });
    }

    private UserAccount findUserBy(String condition, StatementBinder binder) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT " + USER_COLUMNS + " FROM users WHERE " + condition)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? readUser(rs) : null;
                }
            }
        }
    }

    private static UserAccount readUser(ResultSet rs) throws SQLException {
        return new UserAccount(rs.getLong("id"), rs.getString("name"), Role.valueOf(rs.getString("role")),
                rs.getString("feed_key"), FeedStore.parseInstant(rs.getString("created_at")),
                FeedStore.parseInstant(rs.getString("last_seen_at")), rs.getBoolean("disabled"),
                rs.getBoolean("has_token"), rs.getBoolean("has_password"));
    }

    public UserAccount createUser(String name, Role role, String tokenHash, String feedKey)
            throws SQLException {
        if (role != Role.MEMBER) throw new IllegalArgumentException("只能创建成员账户。");
        return createAccount(name, tokenHash, null, feedKey);
    }

    public UserAccount createAccount(String name, String tokenHash, String passwordHash, String feedKey)
            throws SQLException {
        synchronized (store) {
            return store.inTransaction(() -> {
                try (PreparedStatement ps = store.conn.prepareStatement("""
                        INSERT INTO users (name, role, password_hash, feed_key, created_at)
                        VALUES (?, 'MEMBER', ?, ?, ?) ON CONFLICT(name) DO NOTHING RETURNING id""")) {
                    ps.setString(1, name);
                    ps.setString(2, passwordHash);
                    ps.setString(3, feedKey);
                    ps.setString(4, Instant.now().toString());
                    long id;
                    try (ResultSet row = ps.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("这个账户名称已被使用。");
                        id = row.getLong(1);
                    }
                    if (tokenHash != null) updateUserTokenHash(id, tokenHash);
                    return findUser(id);
                }
            });
        }
    }

    public void updateUserTokenHash(long id, String tokenHash) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    INSERT INTO account_tokens(user_id, name, token_hash, is_primary, created_at)
                    SELECT id, '主令牌', ?, 1, ? FROM users WHERE id = ?
                    ON CONFLICT(user_id) WHERE is_primary = 1 DO UPDATE
                    SET token_hash = excluded.token_hash, created_at = excluded.created_at, legacy_cookie = 0
                    WHERE token_hash <> excluded.token_hash""")) {
                ps.setString(1, tokenHash);
                ps.setString(2, Instant.now().toString());
                ps.setLong(3, id);
                ps.executeUpdate();
            }
        }
    }

    public void updateUserFeedKey(long id, String feedKey) throws SQLException {
        updateUser("feed_key = ?", id, ps -> ps.setString(1, feedKey));
    }

    public void setUserDisabled(long id, boolean disabled) throws SQLException {
        if (id == UserAccount.OWNER_ID && disabled) throw new IllegalArgumentException("不能停用初始管理员。");
        updateUser("disabled = ?", id, ps -> ps.setBoolean(1, disabled));
    }

    public void touchUser(long id, Instant seenAt) throws SQLException {
        updateUser("last_seen_at = ?", id, ps -> ps.setString(1, seenAt.toString()));
    }

    private void updateUser(String assignment, long id, StatementBinder binder) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "UPDATE users SET " + assignment + " WHERE id = ?")) {
                binder.bind(ps);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    /** Deletes the account together with everything it bookmarked or read, atomically. */
    public void deleteUser(long id) throws SQLException {
        if (id == UserAccount.OWNER_ID) throw new IllegalArgumentException("不能删除初始管理员。");
        synchronized (store) {
            store.inTransaction(() -> {
                store.reading().deleteAll(id);
                for (String sql : List.of("DELETE FROM account_sessions WHERE user_id = ?",
                        "DELETE FROM account_tokens WHERE user_id = ?", "DELETE FROM pick_feedback WHERE user_id = ?",
                        "DELETE FROM users WHERE id = ?")) {
                    try (PreparedStatement ps = store.conn.prepareStatement(sql)) {
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                }
                return null;
            });
        }
    }
}
