package app.store;

import app.auth.AccountToken;
import app.auth.UserAccount;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class AccountCredentialsStore {
    public record Password(long userId, String hash) {
        @Override
        public String toString() {
            return "Password[userId=" + userId + "]";
        }
    }

    private final FeedStore store;

    AccountCredentialsStore(FeedStore store) {
        this.store = store;
    }

    public List<AccountToken> listTokens(long userId, boolean protectPrimary) throws SQLException {
        return store.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id, name, created_at, is_primary FROM account_tokens
                    WHERE user_id = ? ORDER BY is_primary DESC, id DESC""")) {
                ps.setLong(1, userId);
                var tokens = new ArrayList<AccountToken>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        boolean primary = rs.getBoolean("is_primary");
                        tokens.add(new AccountToken(rs.getLong("id"), rs.getString("name"),
                                FeedStore.parseInstant(rs.getString("created_at")), primary,
                                primary && protectPrimary));
                    }
                }
                return tokens;
            }
        });
    }

    public UserAccount addToken(long userId, String name, String hash) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    INSERT INTO account_tokens(user_id, name, token_hash, created_at)
                    SELECT id, ?, ?, ? FROM users WHERE id = ?""")) {
                ps.setString(1, name);
                ps.setString(2, hash);
                ps.setString(3, Instant.now().toString());
                ps.setLong(4, userId);
                if (ps.executeUpdate() == 0) throw new IllegalArgumentException("账户不存在。");
                return store.accounts().findUser(userId);
            }
        }
    }

    public void revokeToken(long userId, long tokenId, boolean protectPrimary) throws SQLException {
        synchronized (store) {
            store.inTransaction(() -> {
                try (PreparedStatement ps = store.conn.prepareStatement("""
                        SELECT t.is_primary, u.password_hash IS NULL AND
                            NOT EXISTS (SELECT 1 FROM account_tokens WHERE user_id = u.id AND id <> t.id) AS last_login
                        FROM account_tokens t JOIN users u ON u.id = t.user_id WHERE u.id = ? AND t.id = ?""")) {
                    ps.setLong(1, userId);
                    ps.setLong(2, tokenId);
                    try (ResultSet row = ps.executeQuery()) {
                        if (!row.next()) return null;
                        if (protectPrimary && row.getBoolean("is_primary")) {
                            throw new IllegalArgumentException("此主令牌由 FETCH_NEWS_ADMIN_TOKEN 管理。");
                        }
                        if (row.getBoolean("last_login")) {
                            throw new IllegalArgumentException("请先设置账户密码或创建另一个登录令牌。");
                        }
                    }
                }
                try (PreparedStatement ps = store.conn.prepareStatement(
                        "DELETE FROM account_tokens WHERE user_id = ? AND id = ?")) {
                    ps.setLong(1, userId);
                    ps.setLong(2, tokenId);
                    ps.executeUpdate();
                }
                return null;
            });
        }
    }

    public Password passwordByName(String name) throws SQLException {
        return password("name = ? AND disabled = 0", name);
    }

    public Password passwordById(long userId) throws SQLException {
        return password("id = ?", userId);
    }

    private Password password(String condition, Object key) throws SQLException {
        return store.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, password_hash FROM users WHERE " + condition)) {
                ps.setObject(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Password(rs.getLong(1), rs.getString(2)) : null;
                }
            }
        });
    }

    public void setPassword(long userId, String expected, String changed, boolean adminReset) throws SQLException {
        synchronized (store) {
            String condition = adminReset ? "id <> 1" : "password_hash IS ? AND disabled = 0";
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "UPDATE users SET password_hash = ? WHERE id = ? AND " + condition)) {
                ps.setString(1, changed);
                ps.setLong(2, userId);
                if (!adminReset) ps.setString(3, expected);
                if (ps.executeUpdate() == 0) throw new IllegalArgumentException("账户或密码已更改，请重新登录。");
            }
        }
    }
}
