package app.auth;

import java.time.Instant;

/**
 * A person who can sign in. Bookmarks and read state are kept per account.
 *
 * <p>The login token is never stored or exposed — only its hash lives in SQLite.
 * The feed key is a separate, read-only secret meant for RSS reader URLs.
 */
public record UserAccount(long id, String name, Role role, String feedKey,
                          Instant createdAt, Instant lastSeenAt, boolean disabled, boolean hasToken) {

    /** Pre-existing single-user data is migrated to this account. */
    public static final long OWNER_ID = 1;

    public boolean admin() {
        return role == Role.ADMIN;
    }

    /** Stand-in used when authentication is disabled or outside an HTTP request. */
    public static UserAccount owner() {
        return new UserAccount(OWNER_ID, "admin", Role.ADMIN, null, null, null, false, false);
    }
}
