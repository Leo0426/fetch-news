package app.admin;

import app.AppRuntime;
import app.ai.AiSettingsPage;
import app.ai.OllamaClient;
import app.auth.CurrentUser;
import app.core.CredentialConfig;
import app.core.RouteConfig;
import app.feishu.FeishuConfig;
import app.store.FeedRefreshState;
import app.store.FeedStore;
import app.store.FetchLogEntry;
import app.store.RouteHealth;
import app.store.StoredItem;
import app.store.SummaryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders admin page fragments via Thymeleaf.
 */
@Component
public class AdminTemplateRenderer {
    private static final Logger log = LoggerFactory.getLogger(AdminTemplateRenderer.class);
    private static final int LOG_LIMIT = 10;
    public static final int PAGE_SIZE = 30;

    private final TemplateEngine engine;
    private final FeedStore feedStore;
    private final AppInfo appInfo;
    private final AppRuntime runtime;
    private final Map<String, String> descriptions;
    private final Map<String, String> strategies;
    private final Map<String, String> fetchDetails;

    // 60-second TTL cache for the health-check SQL query (window function, runs on every page)
    private volatile Set<String> failingCache = Set.of();
    private volatile long failingCacheExpiry = 0L;

    record RouteGroup(String category, List<RouteConfig> routes) {
    }

    record CategoryInfo(String name, long count) {
    }

    record RouteStats(RouteConfig config, String category, String description,
                      int itemCount, String lastFetched) {
    }

    record RssRefreshStatus(String routePath, String status, String statusClass,
                            String lastChecked, String lastSuccess, String lastChanged,
                            String httpStatus, long responseBytes, long durationMs,
                            int consecutiveFailures, String nextRetry, String error) {
    }

    public AdminTemplateRenderer(TemplateEngine engine, FeedStore feedStore,
                                 AppRuntime runtime, AppInfo appInfo) {
        this.engine = engine;
        this.feedStore = feedStore;
        this.appInfo = appInfo;
        this.runtime = runtime;
        this.descriptions = runtime.routeRegistry().descriptions();
        this.strategies = runtime.routeRegistry().strategies();
        this.fetchDetails = runtime.routeRegistry().fetchDetails();
    }

    /**
     * Full admin page (default content: stats section).
     */
    public String page(List<RouteConfig> routes) {
        Context ctx = baseContext(routes);
        ctx.setVariable("viewer", CurrentUser.viewer());
        List<RouteStats> stats = buildRouteStats(routes);
        ctx.setVariable("routeStats", stats);
        ctx.setVariable("hasData", stats.stream().anyMatch(s -> s.itemCount() > 0));
        return engine.process("index/index", ctx);
    }

    /**
     * Stats section — data metrics, loaded into #main-content via HTMX.
     */
    public String statsSection(List<RouteConfig> routes) {
        Context ctx = baseContext(routes);
        List<RouteStats> stats = buildRouteStats(routes);
        ctx.setVariable("routeStats", stats);
        ctx.setVariable("hasData", stats.stream().anyMatch(s -> s.itemCount() > 0));
        return engine.process("index/stats", ctx);
    }

    /**
     * Category section — filtered route list, loaded into #main-content via HTMX.
     */
    public String categorySection(List<RouteConfig> routes, String category) {
        List<RouteConfig> filtered = routes.stream()
                .filter(r -> category.equals(runtime.categoryOf(r)))
                .toList();
        Map<String, app.store.RouteHealth> healthMap = new LinkedHashMap<>();
        if (feedStore != null) {
            try { healthMap = feedStore.fetchState().loadRouteHealth(); } catch (Exception ignored) {}
        }
        Context ctx = new Context();
        ctx.setVariable("category", category);
        ctx.setVariable("routes", filtered);
        ctx.setVariable("templateRoutes", templatePaths(filtered));
        ctx.setVariable("descriptions", descriptions);
        ctx.setVariable("strategies", strategies);
        ctx.setVariable("fetchDetails", fetchDetails);
        ctx.setVariable("abouts", RouteAbout.ABOUTS);
        ctx.setVariable("healthMap", healthMap);
        return engine.process("index/category", ctx);
    }

    /**
     * Routes table fragment — used for OOB refreshes after save/delete.
     *
     * @param outOfBand pass true when returning as an OOB swap from a POST/DELETE handler
     */
    public String routeTable(List<RouteConfig> routes, String q, String filter, boolean outOfBand) {
        List<RouteConfig> filtered = applyFilter(routes, q, filter);
        Context ctx = new Context();
        ctx.setVariable("groups", groupRoutes(filtered));
        ctx.setVariable("templateRoutes", templatePaths(filtered));
        ctx.setVariable("descriptions", descriptions);
        ctx.setVariable("strategies", strategies);
        ctx.setVariable("fetchDetails", fetchDetails);
        ctx.setVariable("abouts", RouteAbout.ABOUTS);
        ctx.setVariable("outOfBand", outOfBand);
        return engine.process("index/table", ctx);
    }

    private Set<String> templatePaths(List<RouteConfig> routes) {
        return routes.stream().filter(runtime::isTemplate).map(RouteConfig::path)
                .collect(Collectors.toSet());
    }

    /**
     * Route edit modal content (loaded into #modal-inner via HTMX).
     */
    public String routeModal(List<RouteConfig> routes, String path, boolean isNew,
                             String message, String error) {
        RouteConfig selected = isNew ? null :
                routes.stream().filter(r -> r.path().equals(path)).findFirst()
                        .orElseGet(() -> routes.isEmpty() ? null : routes.getFirst());
        List<RouteConfig> sourceRoutes = routes.stream()
                .filter(r -> r.path().equals(r.sourcePath()))
                .filter(runtime::isTemplate)
                .toList();
        Context ctx = new Context();
        ctx.setVariable("selected", selected);
        ctx.setVariable("sourceRoutes", sourceRoutes);
        ctx.setVariable("isNew", isNew);
        ctx.setVariable("message", message);
        ctx.setVariable("error", error);
        ctx.setVariable("recentLogs", isNew ? Collections.emptyList() : recentLogs(selected));

        if (selected != null) {
            ctx.setVariable("about", RouteAbout.ABOUTS.get(selected.sourcePath()));
            ctx.setVariable("inheritedCategory", runtime.routeRegistry().categoryOf(selected.sourcePath()));
        }
        ctx.setVariable("topics", runtime.topics());

        // For concrete Bilibili followings routes, expose the UID and stored cookie
        if (selected != null
                && selected.sourcePath().startsWith("/bilibili/followings/")
                && !selected.path().contains(":")) {
            String uid = selected.path().substring(selected.path().lastIndexOf('/') + 1);
            String storedCookie = runtime.credentialConfigStore().getBilibiliCookieForUid(uid);
            ctx.setVariable("cookieUid", uid);
            ctx.setVariable("cookieStored", storedCookie != null ? storedCookie : "");
        }

        return engine.process("index/route-modal", ctx);
    }

    /**
     * Instance-creation modal for a parameterized source route or the generic RSS route.
     * When {@code sourcePath} is {@code /rss}, the modal switches to feedUrl mode.
     */
    public String instanceModal(String sourcePath) {
        List<String> params = Arrays.stream(sourcePath.split("/"))
                .filter(s -> s.startsWith(":"))
                .map(s -> s.substring(1))
                .toList();
        Context ctx = new Context();
        ctx.setVariable("sourcePath", sourcePath);
        ctx.setVariable("params", params);
        ctx.setVariable("isFeedUrl", "/rss".equals(sourcePath));
        ctx.setVariable("defaultRouteTtl", RouteConfig.DEFAULT_ROUTE_CACHE_TTL_SECONDS);
        ctx.setVariable("defaultDetailTtl", RouteConfig.DEFAULT_DETAIL_CACHE_TTL_SECONDS);
        return engine.process("index/instance-modal", ctx);
    }

    /**
     * AI settings section.
     *
     * @param config       current AI config
     * @param extractCount number of cached AI extracts in SQLite
     * @param summaryCount number of cached AI summaries in SQLite
     * @param message      optional save confirmation message
     */
    public String credentialsSettings(CredentialConfig config, String message) {
        Context ctx = new Context();
        ctx.setVariable("config", config);
        ctx.setVariable("message", message);
        return engine.process("index/credentials", ctx);
    }

    public String feishuSettings(FeishuConfig config, List<String> allCategories, String message) {
        Context ctx = new Context();
        ctx.setVariable("config", config);
        ctx.setVariable("allCategories", allCategories);
        ctx.setVariable("message", message);
        return engine.process("index/feishu-settings", ctx);
    }

    public String aiSettings(AiSettingsPage page, int extractCount, int summaryCount, String message) {
        Context ctx = new Context();
        ctx.setVariable("page", page);
        ctx.setVariable("config", page.active());
        ctx.setVariable("editing", page.editing());
        ctx.setVariable("extractCount", extractCount);
        ctx.setVariable("summaryCount", summaryCount);
        ctx.setVariable("message", message);
        return engine.process("index/ai-settings", ctx);
    }

    /**
     * Feed health dashboard section — loaded into #main-content via HTMX.
     *
     * @param routes    all registered route configs (to detect scheduled-but-never-fetched)
     * @param healthMap route path → health stats from fetch_log
     */
    public String healthSection(List<RouteConfig> routes, Map<String, RouteHealth> healthMap) {
        return healthSection(routes, healthMap, List.of());
    }

    public String healthSection(List<RouteConfig> routes, Map<String, RouteHealth> healthMap,
                                List<FeedRefreshState> refreshStates) {
        Map<String, RouteHealth> full = new LinkedHashMap<>(healthMap);
        for (RouteConfig r : routes) {
            if (r.hasSchedule() && !r.path().contains(":") && !full.containsKey(r.path())) {
                full.put(r.path(), RouteHealth.neverFetched(r.path()));
            }
        }
        long healthy = full.values().stream().filter(h -> h.everFetched() && h.healthy()).count();
        long failing  = full.values().stream().filter(h -> h.everFetched() && !h.healthy()).count();
        long never    = full.values().stream().filter(h -> !h.everFetched()).count();
        Context ctx = new Context();
        ctx.setVariable("healthMap", full);
        ctx.setVariable("totalCount",   full.size());
        ctx.setVariable("healthyCount", healthy);
        ctx.setVariable("failingCount", failing);
        ctx.setVariable("neverCount",   never);
        Instant now = Instant.now();
        List<RssRefreshStatus> rssRefreshStatuses = routes.stream()
                .flatMap(route -> {
                    String feedUrl = runtime.rssFeedUrl(route);
                    return refreshStates.stream().filter(state -> feedUrl != null
                            && route.path().equals(state.routePath()) && feedUrl.equals(state.feedUrl()));
                })
                .map(state -> rssRefreshStatus(state, now))
                .toList();
        ctx.setVariable("rssRefreshStatuses", rssRefreshStatuses);
        return engine.process("index/health", ctx);
    }

    private static RssRefreshStatus rssRefreshStatus(FeedRefreshState state, Instant now) {
        boolean waiting = state.nextRetryAt() != null && state.nextRetryAt().isAfter(now);
        boolean failed = state.lastError() != null;
        String status = waiting ? "等待重试"
                : failed ? "刷新失败" : state.lastHttpStatus() == 304 ? "内容未变化" : "刷新成功";
        String statusClass = waiting ? "pill sched" : failed ? "pill off" : "pill on";
        String error = failed ? state.lastError().replace(state.feedUrl(), "[来源地址]") : "—";
        return new RssRefreshStatus(
                state.routePath(), status, statusClass,
                formatRefreshTime(state.lastCheckedAt()), formatRefreshTime(state.lastSuccessAt()),
                formatRefreshTime(state.lastChangedAt()),
                state.lastHttpStatus() > 0 ? Integer.toString(state.lastHttpStatus()) : "—",
                state.responseBytes(), state.lastDurationMs(), state.consecutiveFailures(),
                formatRefreshTime(state.nextRetryAt()), error);
    }

    private static String formatRefreshTime(Instant instant) {
        return instant != null ? instant.toString() : "—";
    }

    /**
     * Inline connectivity test result fragment (swapped into #ai-test-result).
     */
    public String aiTestResult(OllamaClient.ConnectionStatus status, String configuredModel) {
        Context ctx = new Context();
        ctx.setVariable("status", status);
        ctx.setVariable("configuredModel", configuredModel);
        return engine.process("index/ai-test-result", ctx);
    }

    /**
     * Items browser content (loaded into #main-content via HTMX).
     *
     * @param summaries map of item link → AI summary text for items on the current page
     * @param aiEnabled whether to show the AI summary generate button
     */
    public String itemsPanel(String routePath, List<StoredItem> items, int total, int page,
                             Map<String, String> summaries,
                             Map<String, SummaryStatus> summaryStatuses,
                             boolean aiEnabled) {
        int totalPages = total == 0 ? 1 : (total + PAGE_SIZE - 1) / PAGE_SIZE;
        int startItem = total == 0 ? 0 : page * PAGE_SIZE + 1;
        int endItem = Math.min(page * PAGE_SIZE + PAGE_SIZE, total);
        Context ctx = new Context();
        ctx.setVariable("routePath", routePath);
        ctx.setVariable("items", items);
        ctx.setVariable("total", total);
        ctx.setVariable("page", page);
        ctx.setVariable("pageSize", PAGE_SIZE);
        ctx.setVariable("totalPages", totalPages);
        ctx.setVariable("startItem", startItem);
        ctx.setVariable("endItem", endItem);
        ctx.setVariable("pageWindow", computePageWindow(page, totalPages));
        ctx.setVariable("summaries", summaries);
        ctx.setVariable("summaryStatuses", summaryStatuses);
        ctx.setVariable("aiEnabled", aiEnabled);
        return engine.process("index/items", ctx);
    }

    /** Fetch log viewer page. */
    public String logsPage(List<FetchLogEntry> entries, int total, int page, int pageSize,
                           String filter, int todayCount, int errorCount) {
        int totalPages = total == 0 ? 1 : (total + pageSize - 1) / pageSize;
        Context ctx = new Context();
        ctx.setVariable("entries", entries);
        ctx.setVariable("total", total);
        ctx.setVariable("page", page);
        ctx.setVariable("pageSize", pageSize);
        ctx.setVariable("totalPages", totalPages);
        ctx.setVariable("filter", filter);
        ctx.setVariable("todayCount", todayCount);
        ctx.setVariable("errorCount", errorCount);
        ctx.setVariable("pageWindow", computePageWindow(page, totalPages));
        return engine.process("index/logs", ctx);
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private List<RouteStats> buildRouteStats(List<RouteConfig> routes) {
        return routes.stream().map(r -> {
            int count = 0;
            String lastFetched = "—";
            if (feedStore != null && !r.path().contains(":")) {
                try {
                    count = feedStore.items().countItems(r.path());
                } catch (Exception ignored) {
                }
                try {
                    Instant last = feedStore.fetchState().lastFetchedAt(r.path());
                    if (last != null) lastFetched = formatRelative(last);
                } catch (Exception ignored) {
                }
            }
            return new RouteStats(
                    r,
                    runtime.categoryOf(r),
                    descriptions.get(r.path()),
                    count,
                    lastFetched);
        }).toList();
    }

    private String formatRelative(Instant instant) {
        long minutes = Duration.between(instant, Instant.now()).toMinutes();
        if (minutes < 1) return "刚刚";
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时前";
        return hours / 24 + " 天前";
    }

    private Context baseContext(List<RouteConfig> routes) {
        Context ctx = new Context();
        ctx.setVariable("routes", routes);
        ctx.setVariable("descriptions", descriptions);
        ctx.setVariable("strategies", strategies);
        ctx.setVariable("fetchDetails", fetchDetails);
        ctx.setVariable("enabledCount",
                routes.stream().filter(RouteConfig::enabled).count());
        ctx.setVariable("scheduledCount",
                routes.stream().filter(r -> r.scheduleMinutes() > 0).count());
        ctx.setVariable("uptime", appInfo.formattedUptime());
        ctx.setVariable("version", AppInfo.VERSION);
        ctx.setVariable("categoryList", computeCategoryList(routes));
        ctx.setVariable("failingCategories", computeFailingCategories());
        return ctx;
    }

    /** Invalidates the failing-categories cache so the next page load re-queries. */
    public void invalidateHealthCache() {
        failingCacheExpiry = 0L;
    }

    private Set<String> computeFailingCategories() {
        long now = System.currentTimeMillis();
        if (now < failingCacheExpiry) return failingCache;
        if (feedStore == null) return Set.of();
        try {
            Set<String> result = feedStore.fetchState().loadRouteHealth().values().stream()
                    .filter(h -> h.everFetched() && !h.healthy())
                    .map(h -> runtime.categoryOf(h.routePath()))
                    .collect(Collectors.toSet());
            failingCache = result;
            failingCacheExpiry = now + 60_000L;
            return result;
        } catch (Exception e) {
            return Set.of();
        }
    }

    private List<CategoryInfo> computeCategoryList(List<RouteConfig> routes) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (RouteConfig r : routes) {
            if (r.path().equals(r.sourcePath())) {
                String cat = runtime.categoryOf(r);
                counts.merge(cat, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .map(e -> new CategoryInfo(e.getKey(), e.getValue()))
                .toList();
    }

    List<RouteGroup> groupRoutes(List<RouteConfig> routes) {
        LinkedHashMap<String, List<RouteConfig>> map = new LinkedHashMap<>();
        for (RouteConfig r : routes) {
            String cat = runtime.categoryOf(r);
            map.computeIfAbsent(cat, k -> new ArrayList<>()).add(r);
        }
        return map.entrySet().stream().map(e -> new RouteGroup(e.getKey(), e.getValue())).toList();
    }

    private List<RouteConfig> applyFilter(List<RouteConfig> routes, String q, String filter) {
        String search = q == null ? "" : q.trim().toLowerCase();
        return routes.stream()
                .filter(r -> search.isEmpty() || r.path().toLowerCase().contains(search))
                .filter(r -> switch (filter == null ? "all" : filter) {
                    case "enabled" -> r.enabled();
                    case "disabled" -> !r.enabled();
                    case "scheduled" -> r.scheduleMinutes() > 0;
                    default -> true;
                })
                .toList();
    }

    private List<Integer> computePageWindow(int current, int total) {
        if (total <= 7) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < total; i++) all.add(i);
            return all;
        }
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        set.add(0);
        for (int i = Math.max(0, current - 2); i <= Math.min(total - 1, current + 2); i++) set.add(i);
        set.add(total - 1);
        List<Integer> result = new ArrayList<>();
        int prev = -2;
        for (int p : set) {
            if (prev >= 0 && p > prev + 1) result.add(-1);
            result.add(p);
            prev = p;
        }
        return result;
    }

    private List<FetchLogEntry> recentLogs(RouteConfig selected) {
        if (feedStore == null || selected == null) return Collections.emptyList();
        try {
            return feedStore.fetchState().getRecentLogs(selected.path(), LOG_LIMIT);
        } catch (Exception e) {
            log.warn("failed to load fetch logs for {}: {}", selected.path(), e.getMessage());
            return Collections.emptyList();
        }
    }
}
