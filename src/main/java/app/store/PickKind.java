package app.store;

/** The two triage labels. */
public enum PickKind {
    MUST_KNOW("must"),
    QUALITY("quality");

    private final String id;

    PickKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static PickKind of(String id) {
        for (PickKind kind : values()) {
            if (kind.id.equals(id)) return kind;
        }
        throw new IllegalArgumentException("Unknown pick kind: " + id);
    }
}
