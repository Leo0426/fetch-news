package app.auth;

/**
 * Access level carried by a user's token. Admins manage the instance; members only read.
 */
public enum Role {
    ADMIN("管理员"),
    MEMBER("成员");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
