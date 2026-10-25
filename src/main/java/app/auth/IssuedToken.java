package app.auth;

/**
 * A freshly generated login token. The plain token exists only in this value and is shown once.
 */
public record IssuedToken(UserAccount user, String token) {}
