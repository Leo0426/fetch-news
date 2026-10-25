package app.reader;

import app.store.ItemPick;
import app.store.PickFeedback;
import app.store.PickKind;
import java.util.Map;

/**
 * Triage labels on a reader page together with the current reader's verdicts on them.
 *
 * @param agreed    the reader's "准" verdicts in the feedback window
 * @param disagreed the reader's "不准" verdicts in the feedback window
 */
public record ReaderPicks(Map<String, ItemPick> labels, Map<String, PickFeedback> feedback,
                          int agreed, int disagreed) {
    public static final ReaderPicks NONE = new ReaderPicks(Map.of(), Map.of(), 0, 0);

    public ItemPick label(String link) {
        return labels.get(link);
    }

    public boolean isEmpty() {
        return labels.isEmpty();
    }

    /** Returns the reader's verdict on one label: "up", "down" or "". */
    public String verdict(String link, String kind) {
        String verdict = feedback.getOrDefault(link, PickFeedback.NONE).verdict(PickKind.of(kind));
        return verdict == null ? "" : verdict;
    }

    public int feedbackCount() {
        return agreed + disagreed;
    }

    public int accuracyPercent() {
        return feedbackCount() == 0 ? 0 : Math.round(100f * agreed / feedbackCount());
    }
}
