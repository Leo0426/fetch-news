package app.store;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;

/**
 * A real store whose summary finishes exactly when it is first found missing, reproducing a summary
 * that completes between a reader's summary read and status read.
 */
public final class RacingSummaryStore {
    private RacingSummaryStore() {}

    public static FeedStore completingWith(Path database, ObjectMapper mapper, String summary) throws SQLException {
        FeedStore store = new FeedStore(database, mapper) {
            private AiCache racing;

            @Override public AiCache aiCache() {
                if (racing == null) racing = race(super.aiCache(), this, summary);
                return racing;
            }
        };
        return store;
    }

    private static AiCache race(AiCache real, FeedStore store, String summary) {
        AiCache racing = spy(real);
        try {
            doAnswer(call -> {
                String link = call.getArgument(0);
                String saved = real.loadSummary(link);
                if (saved == null) {
                    real.saveSummary(link, summary, "fixture");
                    store.summaryQueue().markFinished(link, Instant.now(), Instant.now(), "fixture", null);
                }
                return saved;
            }).when(racing).loadSummary(anyString());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return racing;
    }
}
