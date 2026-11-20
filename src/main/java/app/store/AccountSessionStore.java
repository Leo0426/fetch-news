package app.store;

import app.auth.UserAccount;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

public final class AccountSessionStore {
    private final FeedStore store;

    AccountSessionStore(FeedStore store) {
        this.store = store;
    }

    public UserAccount issueForToken(String tokenHash, boolean primaryOnly, String sessionHash, Instant expires)
            throws SQLException {
        String selection = """
                SELECT ?, u.id, t.id, ? FROM account_tokens t JOIN users u ON u.id = t.user_id
                WHERE t.token_hash = ? AND u.disabled = 0""";
        if (primaryOnly) selection += " AND t.is_primary = 1 AND t.legacy_cookie = 1";
        return issue(selection, sessionHash, expires, tokenHash, null);
    }

    public UserAccount issueForPassword(long userId, String verifiedHash, String sessionHash, Instant expires)
            throws SQLException {
        return issue("SELECT ?, id, NULL, ? FROM users WHERE password_hash = ? AND id = ? AND disabled = 0",
                sessionHash, expires, verifiedHash, userId);
    }

    private UserAccount issue(String selection, String sessionHash, Instant expires, String credential, Long userId)
            throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "INSERT INTO account_sessions(session_hash, user_id, token_id, expires_at) " + selection
                            + " RETURNING user_id")) {
                ps.setString(1, sessionHash);
                ps.setLong(2, expires.getEpochSecond());
                ps.setString(3, credential);
                if (userId != null) ps.setLong(4, userId);
                long id;
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    id = rs.getLong(1);
                }
                return store.accounts().findUser(id);
            }
        }
    }

    public void remove(String hash) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "DELETE FROM account_sessions WHERE session_hash = ?")) {
                ps.setString(1, hash);
                ps.executeUpdate();
            }
        }
    }

    public void expire(Instant now) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "DELETE FROM account_sessions WHERE expires_at <= ?")) {
                ps.setLong(1, now.getEpochSecond());
                ps.executeUpdate();
            }
        }
    }
}
