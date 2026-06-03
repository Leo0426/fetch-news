package app;

import app.core.Feed;
import app.core.FeedItem;
import app.core.RouteContext;
import app.core.RouteException;
import app.core.RouteMatch;
import app.store.FeedStore;
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

    /**
     * Creates a fetcher backed by the supplied store.
     * Pass {@code null} to disable persistence (unit-test path).
     *
     * @param feedStore store used for item persistence and fetch logging, or {@code null}
     */
    public FeedFetcher(FeedStore feedStore) {
        this.feedStore = feedStore;
    }

    /**
     * Invokes the handler, persists the resulting items, and logs the outcome.
     *
     * @param match resolved handler and context carrying path parameters
     * @param context fully built request context (includes query params and TTL)
     * @param routePath canonical route path used as the persistence key
     * @param limit maximum number of items to return and persist; 0 means unlimited
     * @return fetched and optionally limited feed
     * @throws Exception when the handler fails
     */
    public Feed fetch(RouteMatch match, RouteContext context, String routePath, int limit)
            throws Exception {
        if (feedStore == null) {
            return applyLimit(match.handler().handle(context), limit);
        }
        Instant start = Instant.now();
        try {
            Feed feed = applyLimit(match.handler().handle(context), limit);
            long ms = Duration.between(start, Instant.now()).toMillis();
            persist(routePath, feed.items(), ms);
            return feed;
        } catch (Exception e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            logError(routePath, ms, e);
            throw e;
        }
    }

    private void persist(String path, List<FeedItem> items, long ms) {
        try {
            int newCount = feedStore.saveItems(path, items);
            feedStore.logFetch(path, Instant.now(), ms, items.size(), newCount, null);
        } catch (Exception e) {
            log.warn("failed to persist feed items for {}: {}", path, e.getMessage());
        }
    }

    private void logError(String path, long ms, Exception e) {
        try {
            String detail = e instanceof RouteException re ? re.getMessage() : e.getClass().getSimpleName();
            feedStore.logFetch(path, Instant.now(), ms, 0, 0, detail);
        } catch (Exception dbEx) {
            log.warn("failed to log fetch error for {}: {}", path, dbEx.getMessage());
        }
    }

    private static Feed applyLimit(Feed feed, int limit) {
        if (limit <= 0 || feed.items().size() <= limit) return feed;
        return new Feed(feed.title(), feed.link(), feed.description(), feed.items().subList(0, limit));
    }
}
