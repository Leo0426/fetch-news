package app.fetch;

import app.AppRuntime;
import app.ai.AiConfigStore;
import app.ai.ArticleSummarizer;
import app.core.Feed;
import app.core.FetchDeferredException;
import app.core.RouteConfig;
import app.core.RouteContext;
import app.core.RouteMatch;
import app.feishu.FeishuPusher;
import app.store.FeedRefreshState;
import app.store.FeedStore;
import app.store.FetchLogEntry;
import app.store.StoredItem;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final int AUTO_SUMMARY_LIMIT = 5;
    private static final int BACKOFF_STEPS = 6;

    private final AppRuntime runtime;
    private final FeedStore feedStore;
    private final FeedFetcher feedFetcher;
    private final ArticleSummarizer articleSummarizer;
    private final AiConfigStore aiConfigStore;
    private final FeishuPusher feishuPusher;
    private final ExecutorService executor;
    private final Map<String, FetchProgress> progressByPath = new ConcurrentHashMap<>();

    @Autowired
    public FetchScheduler(AppRuntime runtime, FeedStore feedStore,
                          ArticleSummarizer articleSummarizer,
                          AiConfigStore aiConfigStore, FeishuPusher feishuPusher) {
        this(runtime, feedStore, articleSummarizer, aiConfigStore, feishuPusher,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    FetchScheduler(AppRuntime runtime, FeedStore feedStore, ArticleSummarizer articleSummarizer,
                   AiConfigStore aiConfigStore, FeishuPusher feishuPusher, ExecutorService executor) {
        this.runtime = runtime;
        this.feedStore = feedStore;
        this.feedFetcher = new FeedFetcher(feedStore, runtime.fetchCoordinator());
        this.articleSummarizer = articleSummarizer;
        this.aiConfigStore = aiConfigStore;
        this.feishuPusher = feishuPusher;
        this.executor = executor;
    }

    public FetchProgress progress(String path) {
        FetchProgress progress = progressByPath.get(path);
        return progress != null && matchesCurrentSource(progress) ? progress : null;
    }

    public boolean busyWithDifferentSource(String path) {
        FetchProgress progress = progressByPath.get(path);
        return progress != null && progress.running() && !matchesCurrentSource(progress);
    }

    private boolean matchesCurrentSource(FetchProgress progress) {
        RouteConfig requested = progress.config();
        RouteConfig current = runtime.routeConfigStore().get(requested.path());
        return requested.sourcePath().equals(current.sourcePath())
                && Objects.equals(requested.feedUrl(), current.feedUrl())
                && requested.detailCacheTtlSeconds() == current.detailCacheTtlSeconds();
    }

    /** Submits an immediate background fetch for {@code path}; returns without waiting. */
    public void triggerFetch(String path) {
        submit(runtime.routeConfigStore().get(path));
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
        Map<String, String> categoryByPath = new HashMap<>();
        for (RouteConfig config : routes) categoryByPath.put(config.path(), runtime.categoryOf(config));

        for (RouteConfig config : routes) {
            if (!config.enabled() || !config.hasSchedule()) continue;
            if (isDue(config, now)) {
                submit(config);
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
            String feedUrl = feedStore == null ? null : runtime.rssFeedUrl(config);
            if (feedUrl != null) {
                FeedRefreshState refresh = feedStore.fetchState().loadFeedRefreshState(config.path(), feedUrl);
                if (refresh != null && refresh.nextRetryAt() != null) {
                    return !refresh.nextRetryAt().isAfter(now);
                }
                last = refresh == null ? null : refresh.lastCheckedAt();
            } else {
                List<FetchLogEntry> recent = feedStore == null ? List.of()
                        : feedStore.fetchState().getRecentLogs(config.path(), BACKOFF_STEPS + 1);
                int failures = leadingFailures(recent);
                if (failures > 0) {
                    // Without this a failing route is due again on every tick, since it has no recent success.
                    return !now.isBefore(recent.getFirst().fetchedAt().plus(retryDelay(failures)));
                }
                last = recent.isEmpty() ? null : recent.getFirst().fetchedAt();
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

    private static int leadingFailures(List<FetchLogEntry> newestFirst) {
        int failures = 0;
        while (failures < newestFirst.size() && !newestFirst.get(failures).isOk()) failures++;
        return failures;
    }

    /** The RSS refresh schedule (GenericRssRoute): 1, 2, 4 … minutes, at most an hour. */
    static Duration retryDelay(int failures) {
        return Duration.ofMinutes(Math.min(60, 1L << Math.min(failures - 1, BACKOFF_STEPS)));
    }

    /** Fetches on the caller's thread unless the route is already being fetched. */
    public void fetchNow(RouteConfig config) {
        FetchProgress started = begin(config);
        if (started != null) execute(started);
    }

    private FetchProgress begin(RouteConfig config) {
        FetchProgress started = new FetchProgress(config, FetchProgress.State.RUNNING, Instant.now(),
                null, 0, 0, null, null);
        FetchProgress accepted = progressByPath.compute(config.path(), (path, previous) ->
                previous != null && previous.running() ? previous : started);
        return accepted == started ? started : null;
    }

    private void submit(RouteConfig config) {
        FetchProgress started = begin(config);
        if (started == null) return;
        try {
            executor.submit(() -> execute(started));
        } catch (RuntimeException failure) {
            complete(started, FetchProgress.State.FAILED, 0, 0, errorMessage(failure), null,
                    latestLogId(config.path()));
            throw failure;
        }
    }

    private void execute(FetchProgress started) {
        RouteConfig config = started.config();
        long previousLogId = latestLogId(config.path());
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
            FetchProgress completed = resultFromLog(started, previousLogId, feed.items().size());
            try {
                pushImmediateItems(config);
                enqueueSummaries(config, started.startedAt());
            } catch (Exception failure) {
                log.warn("[sched] post-fetch processing failed for {}: {}", config.path(), failure.getMessage());
            }
            progressByPath.replace(config.path(), started, completed);
        } catch (FetchDeferredException deferred) {
            complete(started, FetchProgress.State.DEFERRED, 0, 0, deferred.getMessage(), deferred.retryAt(),
                    Math.max(previousLogId, latestLogId(config.path())));
        } catch (Exception e) {
            log.warn("[sched] error {}: {}", config.path(), e.getMessage());
            complete(started, FetchProgress.State.FAILED, 0, 0, errorMessage(e), retryAt(config),
                    Math.max(previousLogId, latestLogId(config.path())));
        } catch (Error error) {
            complete(started, FetchProgress.State.FAILED, 0, 0, errorMessage(error), null,
                    Math.max(previousLogId, latestLogId(config.path())));
            throw error;
        }
    }

    private long latestLogId(String path) {
        if (feedStore == null) return -1;
        try {
            FetchLogEntry entry = feedStore.fetchState().latestFetchLog(path);
            return entry == null ? 0 : entry.id();
        } catch (Exception failure) {
            log.warn("[sched] failed to read fetch log for {}: {}", path, failure.getMessage());
            return -1;
        }
    }

    private FetchProgress resultFromLog(FetchProgress started, long previousLogId, int itemCount) {
        if (feedStore != null && previousLogId >= 0) {
            try {
                FetchLogEntry entry = feedStore.fetchState().latestFetchLog(started.config().path());
                if (entry != null && entry.id() > previousLogId) {
                    return finished(started, entry.isOk() ? FetchProgress.State.SUCCEEDED : FetchProgress.State.FAILED,
                            entry.itemCount(), entry.newItemCount(), entry.errorDetail(), null, entry.id());
                }
            } catch (Exception failure) {
                log.warn("[sched] failed to confirm fetch persistence for {}: {}",
                        started.config().path(), failure.getMessage());
            }
        }
        return finished(started, FetchProgress.State.UNCONFIRMED, itemCount, 0,
                "抓取已结束，但无法确认文章保存结果，请查看抓取记录。", null, previousLogId);
    }

    private Instant retryAt(RouteConfig config) {
        if (feedStore == null) return null;
        try {
            String feedUrl = runtime.rssFeedUrl(config);
            FeedRefreshState refresh = feedUrl == null ? null : feedStore.fetchState().loadFeedRefreshState(config.path(), feedUrl);
            return refresh == null ? null : refresh.nextRetryAt();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void complete(FetchProgress started, FetchProgress.State state, int itemCount,
                          int newItemCount, String error, Instant retryAt, long logId) {
        progressByPath.replace(started.config().path(), started,
                finished(started, state, itemCount, newItemCount, error, retryAt, logId));
    }

    private static FetchProgress finished(FetchProgress started, FetchProgress.State state, int itemCount,
                                          int newItemCount, String error, Instant retryAt, long logId) {
        return new FetchProgress(started.config(), state, started.startedAt(), Instant.now(),
                itemCount, newItemCount, error, retryAt, logId);
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void enqueueSummaries(RouteConfig config, Instant fetchStartedAt) {
        if (!config.autoSummary() || articleSummarizer == null || feedStore == null
                || aiConfigStore == null || !aiConfigStore.get().enabled()) return;
        try {
            List<String> links = feedStore.items().loadNewItemsSince(config.path(), fetchStartedAt).stream()
                    .limit(AUTO_SUMMARY_LIMIT).map(StoredItem::link).toList();
            articleSummarizer.enqueueLinks(links);
        } catch (Exception e) {
            log.warn("[sched] summary enqueue failed: {}", e.toString());
        }
    }

    private void pushImmediateItems(RouteConfig config) {
        if (feishuPusher == null || feedStore == null) return;
        String category = runtime.categoryOf(config);
        feishuPusher.pushRouteItems(config.path(), category, config.displayName(), feedStore);
    }
}
