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
            "id, name, role, feed_key, created_at, last_seen_at, disabled, token_hash IS NOT NULL AS has_token";

    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private final FeedStore store;

    AccountStore(FeedStore store) {
        this.store = store;
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
    }

    public List<UserAccount> listUsers() throws SQLException {
        synchronized (store) {
            List<UserAccount> result = new ArrayList<>();
            try (Statement s = store.conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT " + USER_COLUMNS + " FROM users ORDER BY id")) {
                while (rs.next()) result.add(readUser(rs));
            }
            return result;
        }
    }

    public UserAccount findUser(long id) throws SQLException {
        return findUserBy("id = ?", ps -> ps.setLong(1, id));
    }

    /** Finds the enabled account holding this login-token hash. */
    public UserAccount findUserByTokenHash(String tokenHash) throws SQLException {
        return findUserBy("token_hash = ? AND disabled = 0", ps -> ps.setString(1, tokenHash));
    }

    /** Finds the enabled account owning this feed key. */
    public UserAccount findUserByFeedKey(String feedKey) throws SQLException {
        return findUserBy("feed_key = ? AND disabled = 0", ps -> ps.setString(1, feedKey));
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
                rs.getBoolean("has_token"));
    }

    public UserAccount createUser(String name, Role role, String tokenHash, String feedKey)
            throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    INSERT INTO users (name, role, token_hash, feed_key, created_at) VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, role.name());
                ps.setString(3, tokenHash);
                ps.setString(4, feedKey);
                ps.setString(5, Instant.now().toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return findUser(keys.getLong(1));
                }
            }
        }
    }

    public void updateUserTokenHash(long id, String tokenHash) throws SQLException {
        updateUser("token_hash = ?", id, ps -> ps.setString(1, tokenHash));
    }

    public void updateUserFeedKey(long id, String feedKey) throws SQLException {
        updateUser("feed_key = ?", id, ps -> ps.setString(1, feedKey));
    }

    public void setUserDisabled(long id, boolean disabled) throws SQLException {
        updateUser("disabled = ?", id, ps -> ps.setBoolean(1, disabled));
    }

    public void touchUser(long id, Instant seenAt) throws SQLException {
        updateUser("last_seen_at = ?", id, ps -> ps.setString(1, seenAt.toString()));
    }

    private void updateUser(String assignment, long id, StatementBinder binder) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("UPDATE users SET " + assignment + " WHERE id = ?")) {
                binder.bind(ps);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    /** Deletes the account together with everything it bookmarked or read, atomically. */
    public void deleteUser(long id) throws SQLException {
        synchronized (store) {
            store.inTransaction(() -> {
                store.reading().deleteAll(id);
                try (PreparedStatement ps = store.conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                return null;
            });
        }
    }
}
