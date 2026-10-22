package app.subscriptions;

import app.AppRuntime;
import app.FetchProgress;
import app.FetchScheduler;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteError;
import app.core.RouteException;
import app.store.FeedRefreshState;
import app.store.FeedStore;
import app.store.FetchLogEntry;
import app.subscriptions.SubscriptionPage.Source;
import app.subscriptions.SubscriptionPage.Status;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SubscriptionService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final AppRuntime runtime;
    private final FeedStore feedStore;
    private final FetchScheduler scheduler;

    public SubscriptionService(AppRuntime runtime, FeedStore feedStore, FetchScheduler scheduler) {
        this.runtime = runtime;
        this.feedStore = feedStore;
        this.scheduler = scheduler;
    }

    public Source createRss(String name, String feedUrl) {
        String label = clean(name);
        String url = clean(feedUrl);
        if (label.isEmpty() || label.codePointCount(0, label.length()) > 80)
            throw new IllegalArgumentException("请输入 1–80 个字符的订阅名称。");
        validateRssUrl(url);
        synchronized (runtime.routeConfigStore()) {
            List<RouteConfig> configs = configurations();
            if (configs.stream().anyMatch(config -> label.equalsIgnoreCase(config.displayName())))
                throw new IllegalArgumentException("这个名称已被使用，请换一个名称；原有订阅不会更改。");
            if (configs.stream().anyMatch(config -> url.equals(config.feedUrl())))
                throw new IllegalArgumentException("这个订阅地址已添加，请回到订阅来源查看或恢复订阅。");
            String path = "/feeds/" + UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
            if (configs.stream().anyMatch(config -> path.equals(config.path()))
                    || !runtime.routeConfigStore().get(path).equals(RouteConfig.defaults(path)))
                throw new IllegalArgumentException("这个订阅地址对应的来源已存在，请回到订阅来源查看。");
            RouteConfig config = new RouteConfig(path, "/rss", false,
                    RouteConfig.DEFAULT_ROUTE_CACHE_TTL_SECONDS, RouteConfig.DEFAULT_DETAIL_CACHE_TTL_SECONDS,
                    0, null, url, label);
            metadata(config);
            runtime.routeConfigStore().save(config);
            return source(config);
        }
    }

    public void start(String path, Integer scheduleMinutes) {
        synchronized (runtime.routeConfigStore()) {
            RouteConfig config = configuredSource(path);
            ensureSourceIsAvailable(path);
            Route route = metadata(config);
            validateFeed(config);
            if (config.enabled() && config.hasSchedule()) return;
            int minutes = config.hasSchedule() ? config.scheduleMinutes()
                    : scheduleMinutes == null ? recommendation(route) : scheduleMinutes;
            if (!config.hasSchedule() && (minutes < 1 || minutes > 43200))
                throw new IllegalArgumentException("更新间隔需为 1–43200 分钟");
            runtime.routeConfigStore().save(new RouteConfig(path, config.sourcePath(), true,
                    config.routeCacheTtlSeconds(), config.detailCacheTtlSeconds(), minutes,
                    config.scheduleCron(), config.feedUrl(), config.alias()));
            scheduler.triggerFetch(path);
        }
    }

    public void pause(String path) {
        synchronized (runtime.routeConfigStore()) {
            RouteConfig config = configuredSource(path);
            if (!config.enabled()) return;
            runtime.routeConfigStore().save(new RouteConfig(path, config.sourcePath(), false,
                    config.routeCacheTtlSeconds(), config.detailCacheTtlSeconds(), config.scheduleMinutes(),
                    config.scheduleCron(), config.feedUrl(), config.alias()));
        }
    }

    public void refresh(String path) {
        RouteConfig config = configuredSource(path);
        if (!config.enabled()) throw new IllegalArgumentException("请先恢复订阅，再刷新内容");
        ensureSourceIsAvailable(path);
        validateFeed(config);
        scheduler.triggerFetch(path);
    }

    public SubscriptionPage page(String q, String category, String filter) {
        String query = clean(q);
        String selectedCategory = clean(category);
        String selectedFilter = List.of("subscribed", "attention").contains(clean(filter)) ? clean(filter) : "all";
        List<Source> all = configurations().stream().filter(this::visible).map(this::source).toList();
        String search = query.toLowerCase(Locale.ROOT);
        List<Source> selected = all.stream()
                .filter(source -> selectedCategory.isEmpty() || source.category().equals(selectedCategory))
                .filter(source -> (source.name() + " " + source.description() + " " + source.path())
                        .toLowerCase(Locale.ROOT).contains(search))
                .filter(source -> switch (selectedFilter) {
                    case "subscribed" -> source.subscribed();
                    case "attention" -> (source.config().enabled() && !source.config().hasSchedule())
                            || List.of("error", "waiting", "unknown").contains(source.status().kind());
                    default -> true;
                }).toList();
        return new SubscriptionPage(selected, all.stream().map(Source::category).distinct().sorted().toList(),
                query, selectedCategory, selectedFilter, all.size(), (int) all.stream().filter(Source::subscribed).count());
    }

    public Source source(String path) {
        return source(configuredSource(path));
    }

    private Source source(RouteConfig config) {
        String name = config.displayName();
        String description = "自定义来源";
        String category = "自定义";
        int recommended = 60;
        Status status;
        int articles = 0;
        try {
            Route route = metadata(config);
            description = Objects.requireNonNullElse(route.description(), config.sourcePath());
            if (config.alias() == null) name = description.split(" · | — ", 2)[0];
            if (config.sourcePath().equals("/rss")) description = "自定义 RSS / Atom 订阅";
            category = Objects.requireNonNullElse(route.category(), "自定义");
            if (route.path().contains(":")) description = "已配置的" + category + "订阅，可在高级设置中调整来源。";
            recommended = recommendation(route);
            if (feedStore != null) articles = feedStore.countItems(config.path());
            status = status(config);
        } catch (RouteException | IllegalArgumentException e) {
            status = new Status("error", "请检查来源配置", e.getMessage(), null, false);
        } catch (SQLException e) {
            articles = -1;
            status = new Status("unknown", "暂时无法读取保存结果", "请刷新状态重试，或查看抓取日志。", null, false);
        }
        return new Source(config.path(), name, description, category, config, recommended, status, articles);
    }

    private Status status(RouteConfig config) throws SQLException {
        if (scheduler.busyWithDifferentSource(config.path()))
            return new Status("running", "正在结束旧来源的抓取…", "完成后可刷新当前来源，已修改的配置会用于下次抓取。", null, true);
        FetchProgress progress = scheduler.progress(config.path());
        if (progress != null && progress.running())
            return new Status("running", "正在抓取内容…", "完成后会自动显示结果，也可以稍后回到此页查看。", time(progress.startedAt()), true);
        String feedUrl = runtime.rssFeedUrl(config);
        FeedRefreshState refresh = feedStore == null || feedUrl == null ? null
                : feedStore.loadFeedRefreshState(config.path(), feedUrl);
        if (refresh != null && refresh.nextRetryAt() != null && refresh.nextRetryAt().isAfter(Instant.now()))
            return waiting(refresh.nextRetryAt(), refresh.lastError(), refresh.lastCheckedAt(), config);
        if (progress != null && progress.state() == FetchProgress.State.DEFERRED
                && progress.retryAt() != null && progress.retryAt().isAfter(Instant.now()))
            return waiting(progress.retryAt(), progress.error(), progress.finishedAt(), config);
        if (refresh != null && refresh.lastError() != null)
            return new Status("error", "当前地址上次抓取失败", refresh.lastError(), time(refresh.lastCheckedAt()), false);
        FetchLogEntry latest = feedStore == null ? null : feedStore.latestFetchLog(config.path());
        if (progress != null && (progress.logId() < 0 || latest == null || latest.id() <= progress.logId())) {
            switch (progress.state()) {
                case FAILED -> { return new Status("error", "抓取失败", progress.error(), time(progress.finishedAt()), false); }
                case UNCONFIRMED -> { return new Status("unknown", "保存结果待确认", "请刷新状态或查看抓取日志；尚未确认文章保存成功。", time(progress.finishedAt()), false); }
                default -> { }
            }
        }
        if (feedUrl != null && refresh == null && progress == null && latest != null)
            return new Status("idle", "尚未抓取当前地址", "订阅或刷新后会获取此地址的内容，之前保存的文章仍可阅读。", null, false);
        if (latest != null) {
            if (progress == null && feedUrl != null && latest.isOk())
                return new Status("idle", "已有保存记录", "此来源历史抓取已保存内容，可去阅读或刷新确认当前地址的结果。", time(latest.fetchedAt()), false);
            return latest.isOk()
                    ? new Status("success", "更新完成", "读取 " + latest.itemCount() + " 篇，本次新增 " + latest.newItemCount() + " 篇。", time(latest.fetchedAt()), false)
                    : new Status("error", "抓取失败", latest.errorDetail(), time(latest.fetchedAt()), false);
        }
        return new Status("idle", "尚未抓取", "订阅后会立即抓取首批内容，并按设置的频率更新。", null, false);
    }

    private Status waiting(Instant retryAt, String error, Instant checkedAt, RouteConfig config) {
        String action = config.enabled() && config.hasSchedule() ? "之后将自动重试。" : "之后可恢复订阅重试。";
        return new Status("waiting", "等待重试", "上游暂时不可用，" + time(retryAt) + " " + action
                + (error == null ? "" : " " + error), time(checkedAt), false);
    }

    private RouteConfig configuredSource(String path) {
        return configurations().stream().filter(this::visible).filter(config -> config.path().equals(path))
                .findFirst().orElseThrow(() -> new RouteException(RouteError.ROUTE_NOT_FOUND, "未找到此订阅来源，请刷新列表"));
    }

    private List<RouteConfig> configurations() {
        return runtime.routeConfigStore().list(runtime.routeRegistry().paths());
    }

    private boolean visible(RouteConfig config) {
        return config.isSchedulable() && !(config.sourcePath().equals("/rss") && config.feedUrl() == null);
    }

    private Route metadata(RouteConfig config) {
        var handler = runtime.resolveRoute(config.path(), config.sourcePath()).handler();
        return runtime.routeRegistry().routes().stream().filter(route -> route.handler() == handler)
                .findFirst().orElseThrow(() -> new RouteException(RouteError.ROUTE_NOT_FOUND));
    }

    private void validateFeed(RouteConfig config) {
        runtime.rssFeedUrl(config);
    }

    private static void validateRssUrl(String url) {
        if (!url.isEmpty() && url.length() <= 2048) {
            try {
                URI uri = URI.create(url);
                if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && uri.getUserInfo() == null) return;
            } catch (IllegalArgumentException ignored) {
                // Keep invalid draft values for correction before any source request.
            }
        }
        throw new IllegalArgumentException("请输入完整的 http:// 或 https:// RSS / Atom 地址（最多 2048 个字符），地址中不能包含用户名或密码。");
    }

    private void ensureSourceIsAvailable(String path) {
        if (scheduler.busyWithDifferentSource(path))
            throw new IllegalArgumentException("旧来源仍在抓取，请等待结束后再刷新或恢复订阅");
    }

    private int recommendation(Route route) {
        return route.recommendedScheduleMinutes() > 0 ? route.recommendedScheduleMinutes() : 60;
    }

    private static String clean(String value) { return value == null ? "" : value.strip(); }
    private static String time(Instant instant) { return instant == null ? null : TIME.format(instant); }
}
