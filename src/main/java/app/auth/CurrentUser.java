package app.auth;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Resolves the account that {@link AuthFilter} attached to the current HTTP request.
 */
public final class CurrentUser {
    public static final String ATTRIBUTE = CurrentUser.class.getName();

    private CurrentUser() {}

    /**
     * Returns the signed-in account. Code running outside a request (tests, background jobs)
     * acts as the owner; a request that bypassed the filter fails closed.
     */
    public static UserAccount get() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return UserAccount.owner();
        Object user = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (user instanceof UserAccount account) return account;
        throw new IllegalStateException("request has no authenticated user");
    }

    public static long id() {
        return get().id();
    }

    /** Returns the signed-in account, or {@code null} when none is attached (used by templates). */
    public static UserAccount viewer() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        return attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof UserAccount account
                ? account : null;
    }
}
