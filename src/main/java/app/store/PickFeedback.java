package app.store;

import java.util.Set;

/**
 * One reader's verdicts on an article's triage labels: {@code "up"}, {@code "down"} or {@code null}.
 */
public record PickFeedback(String mustKnow, String quality) {
    public static final Set<String> VERDICTS = Set.of("up", "down");
    public static final PickFeedback NONE = new PickFeedback(null, null);

    public String verdict(PickKind kind) {
        return kind == PickKind.MUST_KNOW ? mustKnow : quality;
    }

    public PickFeedback with(PickKind kind, String verdict) {
        return kind == PickKind.MUST_KNOW ? new PickFeedback(verdict, quality) : new PickFeedback(mustKnow, verdict);
    }

    /** A labelled article title with a reader's verdict, used to show the model the readers' taste. */
    public record Example(PickKind kind, String verdict, String title) {}
}
