package app.store;

/**
 * AI triage labels for one article. A non-null reason means the article carries that label.
 */
public record ItemPick(String mustKnowReason, String qualityReason) {
    public boolean mustKnow() {
        return mustKnowReason != null;
    }

    public boolean quality() {
        return qualityReason != null;
    }
}
