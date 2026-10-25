package app.reader;

import app.store.FeedStore;
import app.store.StoredItem;
import app.store.SummaryStatus;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Turns stored articles into reader cards: AI summary and its status, the source excerpt when there
 * is no summary, the cover, and the triage labels with the reader's own verdicts.
 *
 * <p>Every reader page builds its cards here, so a new card field is added in one place.
 */
public final class ReaderItems {
    static final Duration PICK_FEEDBACK_WINDOW = Duration.ofDays(30);

    /** Cards in the order of the stored articles, plus the labels shown on them. */
    public record Page(List<ReaderItem> items, ReaderPicks picks) {}

    private final FeedStore store;

    public ReaderItems(FeedStore store) {
        this.store = store;
    }

    /** Builds cards for {@code stored}, skipping articles without a title or link. */
    public Page load(List<StoredItem> stored, long userId) throws SQLException {
        List<StoredItem> readable = readable(stored);
        List<String> links = readable.stream().map(StoredItem::link).toList();
        Map<String, String> summaries = store.aiCache().loadSummaries(links);
        Map<String, SummaryStatus> statuses = store.summaryQueue().statuses(links);
        Map<String, String> excerpts = store.items().loadExcerpts(links);
        int[] verdicts = store.picks().countFeedback(userId, Instant.now().minus(PICK_FEEDBACK_WINDOW));
        ReaderPicks picks = new ReaderPicks(store.picks().labels(links), store.picks().feedback(userId, links),
                verdicts[0], verdicts[1]);
        List<ReaderItem> items = readable.stream().map(item -> {
            String summary = summaries.get(item.link());
            return new ReaderItem(item.routePath(), item.title(), item.link(), item.pubDate(), item.firstSeenAt(),
                    summary, statuses.get(item.link()), item.image(),
                    summary == null ? excerpts.get(item.link()) : null);
        }).toList();
        return new Page(items, picks);
    }

    /** Cards without AI data or excerpts, for when the enrichment queries fail. */
    public static Page bare(List<StoredItem> stored) {
        return new Page(readable(stored).stream()
                .map(item -> new ReaderItem(item.routePath(), item.title(), item.link(), item.pubDate(),
                        item.firstSeenAt(), null, null, item.image(), null))
                .toList(), ReaderPicks.NONE);
    }

    private static List<StoredItem> readable(List<StoredItem> stored) {
        return stored.stream()
                .filter(item -> item.title() != null && !item.title().isBlank()
                        && item.link() != null && !item.link().isBlank())
                .toList();
    }
}
