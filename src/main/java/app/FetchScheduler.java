package app;

import app.ai.AiConfigStore;
import app.ai.ArticleExtractor;
import app.ai.ArticleSummarizer;
import app.core.Feed;
import app.core.RouteConfig;
import app.core.RouteContext;
import app.core.RouteMatch;
import app.store.FeedStore;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
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
    private final ArticleExtractor articleExtractor;
    private final ArticleSummarizer articleSummarizer;
    private final AiConfigStore aiConfigStore;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public FetchScheduler(AppRuntime runtime, FeedStore feedStore,
                          ArticleExtractor articleExtractor, ArticleSummarizer articleSummarizer,
                          AiConfigStore aiConfigStore) {
        this.runtime = runtime;
        this.feedStore = feedStore;
        this.feedFetcher = new FeedFetcher(feedStore);
        this.articleExtractor = articleExtractor;
        this.articleSummarizer = articleSummarizer;
        this.aiConfigStore = aiConfigStore;
    }

    /** Submits an immediate background fetch for {@code path}; returns without waiting. */
    public void triggerFetch(String path) {
        RouteConfig config = runtime.routeConfigStore().get(path);
        executor.submit(() -> fetch(config));
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
        for (RouteConfig config : routes) {
            if (!config.enabled() || !config.hasSchedule()) continue;
            if (isDue(config, now)) {
                executor.submit(() -> fetch(config));
            }
        }
    }

    private boolean isDue(RouteConfig config, Instant now) {
        try {
            Instant last = feedStore.lastFetchedAt(config.path());
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

    private void fetch(RouteConfig config) {
        log.info("[sched] start {}", config.path());
        try {
            RouteMatch match = runtime.routeRegistry().resolve(config.path());
            RouteContext context = new RouteContext(
                    config.path(),
                    match.context().pathParams(),
                    Map.of(),
                    Duration.ofSeconds(config.detailCacheTtlSeconds()),
                    config.feedUrl());
            Feed feed = feedFetcher.fetch(match, context, config.path(), 0);
            log.info("[sched] done {} — {} items", config.path(), feed.items().size());
            if (aiConfigStore.get().enabled()) {
                log.info("[sched] ai start {}", config.path());
                articleExtractor.enrich(feed.items());
                articleSummarizer.summarize(feed.items());
                log.info("[sched] ai done {}", config.path());
            }
        } catch (Exception e) {
            log.warn("[sched] error {}: {}", config.path(), e.getMessage());
        }
    }
}
