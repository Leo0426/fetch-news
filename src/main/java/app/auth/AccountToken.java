package app.auth;

import java.time.Instant;

/** Display information for a login token. Neither the secret nor its hash is exposed. */
public record AccountToken(long id, String name, Instant createdAt, boolean primary, boolean protectedByEnvironment) {}
