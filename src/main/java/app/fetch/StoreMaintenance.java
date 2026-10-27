package app.fetch;

import app.store.FeedStore;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily housekeeping: prunes old fetch log entries, refreshes planner statistics and reclaims free pages. */
@Component
public class StoreMaintenance {
    private static final Logger log = LoggerFactory.getLogger(StoreMaintenance.class);
    static final Duration FETCH_LOG_RETENTION = Duration.ofDays(30);
    private static final long VACUUM_MIN_FREE_BYTES = 64L << 20;

    private final FeedStore feedStore;

    public StoreMaintenance(FeedStore feedStore) {
        this.feedStore = feedStore;
    }

    @Scheduled(fixedDelay = 24 * 60 * 60_000, initialDelay = 5 * 60_000)
    public void run() {
        if (feedStore == null) return;
        try {
            int pruned = feedStore.fetchState().pruneFetchLog(Instant.now().minus(FETCH_LOG_RETENTION));
            if (pruned > 0) log.info("[maintenance] pruned {} fetch log entries", pruned);
            feedStore.optimize();
            if (feedStore.vacuumIfFragmented(VACUUM_MIN_FREE_BYTES)) log.info("[maintenance] vacuumed database");
        } catch (Exception failure) {
            log.warn("[maintenance] failed: {}", failure.getMessage());
        }
    }
}
