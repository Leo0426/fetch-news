package app.store;

import java.sql.SQLException;
import java.sql.Statement;

/** Migrated in the same transaction as the users and reading-state schemas. */
final class AccountCredentialSchema {
    private AccountCredentialSchema() {}

    static void migrate(Statement s) throws SQLException {
        FeedSchema.addColumnIfMissing(s, "users", "password_hash", "TEXT");
        s.execute("UPDATE users SET role = 'MEMBER' WHERE id <> 1 AND role <> 'MEMBER'");
        s.execute("UPDATE users SET role = 'ADMIN', disabled = 0 WHERE id = 1 AND (role <> 'ADMIN' OR disabled <> 0)");
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_account_owner ON users(role) WHERE role = 'ADMIN'");
        s.execute("""
                CREATE TRIGGER IF NOT EXISTS protect_owner_update BEFORE UPDATE OF role, disabled, id ON users
                WHEN OLD.id = 1 AND (NEW.role <> 'ADMIN' OR NEW.disabled <> 0 OR NEW.id <> 1)
                BEGIN SELECT RAISE(ABORT, 'owner account is protected'); END""");
        s.execute("""
                CREATE TRIGGER IF NOT EXISTS protect_owner_delete BEFORE DELETE ON users WHEN OLD.id = 1
                BEGIN SELECT RAISE(ABORT, 'owner account is protected'); END""");
        s.execute("""
                CREATE TABLE IF NOT EXISTS account_tokens (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE,
                    is_primary INTEGER NOT NULL DEFAULT 0,
                    legacy_cookie INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL
                )""");
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_primary_token"
                + " ON account_tokens(user_id) WHERE is_primary = 1");
        s.execute("CREATE INDEX IF NOT EXISTS account_tokens_user ON account_tokens(user_id)");
        s.execute("""
                INSERT INTO account_tokens (user_id, name, token_hash, is_primary, legacy_cookie, created_at)
                SELECT id, '主令牌', token_hash, 1, 1, created_at FROM users WHERE token_hash IS NOT NULL""");
        s.execute("UPDATE users SET token_hash = NULL WHERE token_hash IS NOT NULL");
        s.execute("""
                CREATE TABLE IF NOT EXISTS account_sessions (
                    session_hash TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    token_id INTEGER,
                    expires_at INTEGER NOT NULL
                )""");
        s.execute("CREATE INDEX IF NOT EXISTS sessions_by_token ON account_sessions(token_id)");
        s.execute("CREATE INDEX IF NOT EXISTS sessions_by_user ON account_sessions(user_id)");
        s.execute("CREATE INDEX IF NOT EXISTS sessions_by_expiry ON account_sessions(expires_at)");
        s.execute("""
                CREATE TRIGGER IF NOT EXISTS forget_rotated_token AFTER UPDATE OF token_hash ON account_tokens
                WHEN OLD.token_hash <> NEW.token_hash
                BEGIN DELETE FROM account_sessions WHERE token_id = OLD.id; END""");
        s.execute("""
                CREATE TRIGGER IF NOT EXISTS forget_revoked_token AFTER DELETE ON account_tokens
                BEGIN DELETE FROM account_sessions WHERE token_id = OLD.id; END""");
        s.execute("""
                CREATE TRIGGER IF NOT EXISTS forget_disabled_sessions AFTER UPDATE OF disabled ON users
                WHEN NEW.disabled <> 0
                BEGIN
                    DELETE FROM account_sessions WHERE user_id = NEW.id;
                    UPDATE account_tokens SET legacy_cookie = 0 WHERE user_id = NEW.id AND legacy_cookie = 1;
                END""");
        s.execute("""
                CREATE TRIGGER IF NOT EXISTS retire_password_sessions AFTER UPDATE OF password_hash ON users
                WHEN NEW.password_hash IS NOT OLD.password_hash
                BEGIN
                    DELETE FROM account_sessions WHERE user_id = NEW.id;
                    UPDATE account_tokens SET legacy_cookie = 0 WHERE user_id = NEW.id AND legacy_cookie = 1;
                END""");
    }
}
