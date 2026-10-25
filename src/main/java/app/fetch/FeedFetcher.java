package app.fetch;

import app.core.Feed;
import app.core.FeedItem;
import app.core.FetchDeferredException;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteMatch;
import app.store.FeedStore;
import app.support.CoverImages;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Invokes a route handler, measures wall-clock duration, persists items, and logs the fetch result.
 *
 * <p>Centralizes the fetch-and-persist cycle shared by the public feed endpoint and the background
 * scheduler. When {@code feedStore} is {@code null} persistence is skipped, which allows unit tests
 * to construct a resource without a database.
 */
public class FeedFetcher {
    private static final Logger log = LoggerFactory.getLogger(FeedFetcher.class);

    private final FeedStore feedStore;
    private final FeedFetchCoordinator coordinator;

    /**
     * Creates a fetcher backed by the supplied store.
     * Pass {@code null} to disable persistence (unit-test path).
     *
     * @param feedStore store used for item persistence and fetch logging, or {@code null}
     */
    public FeedFetcher(FeedStore feedStore) {
        this(feedStore, new FeedFetchCoordinator());
    }

    public FeedFetcher(FeedStore feedStore, FeedFetchCoordinator coordinator) {
        this.feedStore = feedStore;
        this.coordinator = coordinator;
    }

    /**
     * Invokes the handler, persists the resulting items, and logs the outcome.
     *
     * @param match resolved handler and context carrying path parameters
     * @param context fully built request context (includes query params and TTL)
     * @param routePath canonical route path used as the persistence key
     * @return complete fetched feed
     * @throws Exception when the handler fails
     */
    public Feed fetch(RouteMatch match, RouteContext context, String routePath)
            throws Exception {
        return coordinator.fetch(match, context, () -> fetchAndPersist(match, context, routePath));
    }

    private Feed fetchAndPersist(RouteMatch match, RouteContext context, String routePath) throws Exception {
        Instant start = Instant.now();
        try {
            Feed feed = match.handler().handle(context);
            if (feed.items().isEmpty()) {
                throw new RouteException(RouteError.EMPTY_FEED);
            }
            feed = CoverImages.withFallbackCovers(feed);
            long ms = Duration.between(start, Instant.now()).toMillis();
            persist(routePath, feed.items(), ms);
            return feed;
        } catch (Exception e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            if (!(e instanceof FetchDeferredException)) logError(routePath, ms, e);
            throw e;
        }
    }

    private void persist(String path, List<FeedItem> items, long ms) {
        if (feedStore == null) return;
        int newCount;
        try {
            newCount = feedStore.saveItems(path, items);
        } catch (Exception e) {
            log.warn("failed to persist feed items for {}: {}", path, e.getMessage());
            logError(path, ms, persistenceError(e));
            return;
        }
        try {
            feedStore.fetchState().logFetch(path, Instant.now(), ms, items.size(), newCount, null);
        } catch (Exception e) {
            log.warn("failed to log successful fetch for {}: {}", path, e.getMessage());
        }
    }

    private void logError(String path, long ms, Exception e) {
        String detail = e instanceof SQLException ? persistenceError(e)
                : e instanceof RouteException re ? re.getMessage() : e.getClass().getSimpleName();
        logError(path, ms, detail);
    }

    private void logError(String path, long ms, String detail) {
        if (feedStore == null) return;
        try {
            feedStore.fetchState().logFetch(path, Instant.now(), ms, 0, 0, detail);
        } catch (Exception dbEx) {
            log.warn("failed to log fetch error for {}: {}", path, dbEx.getMessage());
        }
    }

    private static String persistenceError(Exception failure) {
        return "persistence failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}
