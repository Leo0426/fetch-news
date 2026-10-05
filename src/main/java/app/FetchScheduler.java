package app;

import app.ai.AiConfigStore;
import app.ai.ArticleSummarizer;
import app.core.Feed;
import app.core.RouteConfig;
import app.core.RouteContext;
import app.core.RouteMatch;
import app.feishu.FeishuPusher;
import app.store.FeedStore;
import app.store.FeedRefreshState;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Ticks every minute and triggers background fetches for routes whose schedule interval has elapsed.
 * Only schedulable routes (no path-param segments) are considered.
 */
@Component
public class FetchScheduler {
    private static final Logger log = LoggerFactory.getLogger(FetchScheduler.class);

    private final AppRuntime runtime;
    private final FeedStore feedStore;
    private final FeedFetcher feedFetcher;
    private final ArticleSummarizer articleSummarizer;
    private final AiConfigStore aiConfigStore;
    private final FeishuPusher feishuPusher;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public FetchScheduler(AppRuntime runtime, FeedStore feedStore,
                          ArticleSummarizer articleSummarizer,
                          AiConfigStore aiConfigStore, FeishuPusher feishuPusher) {
        this.runtime = runtime;
        this.feedStore = feedStore;
        this.feedFetcher = new FeedFetcher(feedStore, runtime.fetchCoordinator());
        this.articleSummarizer = articleSummarizer;
        this.aiConfigStore = aiConfigStore;
        this.feishuPusher = feishuPusher;
    }

    /** Submits an immediate background fetch for {@code path}; returns without waiting. */
    public void triggerFetch(String path) {
        RouteConfig config = runtime.routeConfigStore().get(path);
        executor.submit(() -> fetchNow(config));
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS))
                executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        Instant now = Instant.now();
        List<RouteConfig> routes = runtime.routeConfigStore().list(runtime.routeRegistry().paths());
        Map<String, String> categoryByPath = runtime.routeRegistry().categories();

        for (RouteConfig config : routes) {
            if (!config.enabled() || !config.hasSchedule()) continue;
            if (isDue(config, now)) {
                executor.submit(() -> fetchNow(config));
            }
        }

        // Digest check runs in the background every tick; FeishuPusher gates on cron.
        if (feishuPusher != null && feedStore != null) {
            executor.submit(() ->
                    feishuPusher.checkDigests(now, routes, categoryByPath, feedStore));
        }
    }

    private boolean isDue(RouteConfig config, Instant now) {
        try {
            Instant last;
            if (feedStore != null && "/rss".equals(config.sourcePath()) && config.feedUrl() != null) {
                FeedRefreshState refresh = feedStore.loadFeedRefreshState(config.path(), config.feedUrl());
                if (refresh != null && refresh.nextRetryAt() != null) {
                    return !refresh.nextRetryAt().isAfter(now);
                }
                last = refresh == null ? null : refresh.lastCheckedAt();
            } else {
                last = feedStore != null ? feedStore.lastFetchedAt(config.path()) : null;
            }
            if (config.scheduleCron() != null) {
                ZonedDateTime lastZdt = (last != null ? last : Instant.EPOCH)
                        .atZone(ZoneId.systemDefault());
                ZonedDateTime nextRun = CronExpression.parse(config.scheduleCron()).next(lastZdt);
                return nextRun != null && !nextRun.toInstant().isAfter(now);
            }
            return last == null || now.isAfter(last.plus(Duration.ofMinutes(config.scheduleMinutes())));
        } catch (Exception e) {
            log.warn("[sched] failed to check isDue for {}: {}", config.path(), e.getMessage());
            return false;
        }
    }

    void fetchNow(RouteConfig config) {
        if (!inFlight.add(config.path())) return;
        log.info("[sched] start {}", config.path());
        try {
            RouteMatch match = runtime.resolveRoute(config.path(), config.sourcePath());
            RouteContext context = new RouteContext(
                    config.path(),
                    match.context().pathParams(),
                    Map.of(),
                    Duration.ofSeconds(config.detailCacheTtlSeconds()),
                    config.feedUrl());
            Feed feed = feedFetcher.fetch(match, context, config.path());
            log.info("[sched] done {} — {} items", config.path(), feed.items().size());
            pushImmediateItems(config);
            enqueueSummaries(feed);
        } catch (Exception e) {
            log.warn("[sched] error {}: {}", config.path(), e.getMessage());
        } finally {
            inFlight.remove(config.path());
        }
    }

    private void enqueueSummaries(Feed feed) {
        if (articleSummarizer == null || aiConfigStore == null || !aiConfigStore.get().enabled()) return;
        try {
            articleSummarizer.enqueue(feed.items());
        } catch (Exception e) {
            log.warn("[sched] summary enqueue failed: {}", e.toString());
        }
    }

    private void pushImmediateItems(RouteConfig config) {
        if (feishuPusher == null || feedStore == null) return;
        String category = runtime.routeRegistry().categories()
                .getOrDefault(config.sourcePath(), null);
        if (category == null) return;
        feishuPusher.pushRouteItems(config.path(), category, config.displayName(), feedStore);
    }
}
