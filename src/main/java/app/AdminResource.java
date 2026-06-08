package app;

import app.ai.AiConfig;
import app.CredentialConfig;
import app.CredentialConfigStore;
import app.ai.AiConfigStore;
import app.ai.ArticleSummarizer;
import app.ai.OllamaClient;
import app.core.FeedItem;
import app.core.RouteConfig;
import app.core.RouteError;
import app.core.RouteException;
import app.store.FeedStore;
import app.store.StoredItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/**
 * Serves the index UI and its HTMX fragment endpoints.
 */
@Controller
@RequestMapping("/index")
public class AdminResource {
    private static final Logger log = LoggerFactory.getLogger(AdminResource.class);

    private final AppRuntime runtime;
    private final ObjectMapper objectMapper;
    private final AdminTemplateRenderer templates;
    private final FeedStore feedStore;
    private final AiConfigStore aiConfigStore;
    private final OllamaClient ollamaClient;
    private final ArticleSummarizer articleSummarizer;
    private final FetchScheduler fetchScheduler;

    public AdminResource(AppRuntime runtime, ObjectMapper objectMapper,
                         AdminTemplateRenderer templates, FeedStore feedStore,
                         AiConfigStore aiConfigStore, OllamaClient ollamaClient,
                         ArticleSummarizer articleSummarizer,
                         FetchScheduler fetchScheduler) {
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.templates = templates;
        this.feedStore = feedStore;
        this.aiConfigStore = aiConfigStore;
        this.ollamaClient = ollamaClient;
        this.articleSummarizer = articleSummarizer;
        this.fetchScheduler = fetchScheduler;
    }

    /** Redirects the root path to the index page. */
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String root() {
        return "redirect:/index";
    }

    /** Full index page. */
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String page() {
        return templates.page(routes());
    }

    /**
     * Stats section — data metrics, default main-content for HTMX navigation.
     */
    @GetMapping(value = "/stats", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String stats() {
        return templates.statsSection(routes());
    }

    /**
     * Category section — filtered route list, loaded into #main-content via HTMX.
     */
    @GetMapping(value = "/category", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String category(@RequestParam String name) {
        return templates.categorySection(routes(), name);
    }

    /**
     * Routes table fragment — for HTMX search/filter refreshes.
     */
    @GetMapping(value = "/routes/table", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String routeTable(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String filter) {
        return templates.routeTable(routes(), q, filter, false);
    }

    /**
     * Route edit modal content.
     */
    @GetMapping(value = "/routes/edit", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String editRoute(@RequestParam(required = false) String path) {
        return templates.routeModal(routes(), path, false, null, null);
    }

    /**
     * New mount alias modal content.
     */
    @GetMapping(value = "/routes/new", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String newMount() {
        return templates.routeModal(routes(), null, true, null, null);
    }

    /**
     * Instance creation modal for a parameterized source route.
     */
    @GetMapping(value = "/routes/instance", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String newInstance(@RequestParam String source) {
        return templates.instanceModal(source);
    }

    /**
     * Items browser modal content.
     */
    @GetMapping(value = "/items", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String browseItems(
            @RequestParam String path,
            @RequestParam(defaultValue = "0") int page) {
        int pageSize = AdminTemplateRenderer.PAGE_SIZE;
        int total = 0;
        List<StoredItem> items = Collections.emptyList();
        if (feedStore != null) {
            try {
                total = feedStore.countItems(path);
                items = feedStore.browseItems(path, Math.max(0, page) * pageSize, pageSize);
            } catch (Exception e) {
                log.warn("failed to browse items for {}: {}", path, e.getMessage());
            }
        }
        Map<String, String> summaries = Map.of();
        if (feedStore != null && !items.isEmpty()) {
            try {
                List<String> links = items.stream()
                        .filter(i -> i.link() != null).map(StoredItem::link).toList();
                summaries = feedStore.loadSummaries(links);
            } catch (Exception e) {
                log.warn("failed to load summaries for {}: {}", path, e.getMessage());
            }
        }
        boolean aiEnabled = aiConfigStore != null && aiConfigStore.get().enabled();
        return templates.itemsPanel(path, items, total, Math.max(0, page), summaries, aiEnabled);
    }

    /**
     * Generates (or returns cached) an AI summary for a single item.
     * Returns an HTML fragment to be swapped into the summary cell via HTMX.
     */
    @PostMapping(
            value = "/items/summarize",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String summarizeItem(
            @RequestParam String link,
            @RequestParam(required = false, defaultValue = "") String title) {
        if (!aiConfigStore.get().enabled()) {
            return summaryGenDiv(link, title, "AI 未启用", false);
        }
        try {
            String cached = feedStore.loadSummary(link);
            if (cached == null || cached.isBlank()) {
                FeedItem item = new FeedItem(
                        title.isBlank() ? null : title, link, null, null, null, List.of());
                articleSummarizer.summarize(List.of(item));
                cached = feedStore.loadSummary(link);
            }
            if (cached == null || cached.isBlank()) {
                return summaryGenDiv(link, title, "生成失败，请确认 Ollama 服务正常", true);
            }
            return "<div class=\"item-summary\">" + HtmlUtils.htmlEscape(cached) + "</div>";
        } catch (Exception e) {
            log.warn("admin summarize failed for {}: {}", link, e.getMessage());
            return summaryGenDiv(link, title, HtmlUtils.htmlEscape(e.getMessage()), true);
        }
    }

    /**
     * Submits batch AI summarization for all un-summarized items of a route.
     * Returns an HTML status snippet; processing happens in a background virtual thread.
     */
    @PostMapping(
            value = "/items/summarize-batch",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String summarizeBatch(@RequestParam String path) {
        if (!aiConfigStore.get().enabled()) {
            return "<span class='sum-status err'>AI 未启用，请先在设置中配置 Ollama</span>";
        }
        try {
            List<FeedItem> all = feedStore.loadItems(path);
            List<String> links = all.stream()
                    .filter(i -> i.link() != null && !i.link().isBlank())
                    .map(FeedItem::link).toList();
            Map<String, String> existing = feedStore.loadSummaries(links);
            List<FeedItem> pending = all.stream()
                    .filter(i -> i.link() != null && !i.link().isBlank())
                    .filter(i -> !existing.containsKey(i.link()))
                    .toList();
            if (pending.isEmpty()) {
                return "<span class='sum-status ok'>全部条目已有摘要</span>";
            }
            Thread.startVirtualThread(() -> {
                try { articleSummarizer.summarize(pending); }
                catch (Exception e) { log.warn("batch summarize failed: {}", e.getMessage()); }
            });
            return "<span class='sum-status ok'>已提交 " + pending.size() + " 条，后台生成中</span>";
        } catch (Exception e) {
            log.warn("batch summarize submit failed: {}", e.getMessage());
            return "<span class='sum-status err'>提交失败：" + HtmlUtils.htmlEscape(e.getMessage()) + "</span>";
        }
    }

    /**
     * Save route config from modal form. Returns updated modal + OOB table refresh.
     */
    @PostMapping(
            value = "/routes",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> saveRouteForm(
            @RequestParam String path,
            @RequestParam(required = false) String sourcePath,
            @RequestParam(required = false) String enabled,
            @RequestParam int routeCacheTtlSeconds,
            @RequestParam int detailCacheTtlSeconds,
            @RequestParam(defaultValue = "0") int scheduleMinutes,
            @RequestParam(required = false) String scheduleCron,
            @RequestParam(required = false) String feedUrl,
            @RequestParam(required = false) String alias,
            @RequestParam(defaultValue = "false") boolean isNew) {
        try {
            String cron  = (scheduleCron == null || scheduleCron.isBlank()) ? null : scheduleCron.trim();
            String url   = (feedUrl      == null || feedUrl.isBlank())      ? null : feedUrl.trim();
            String label = (alias        == null || alias.isBlank())        ? null : alias.trim();
            RouteConfig config = new RouteConfig(
                    path,
                    sourcePath == null || sourcePath.isBlank() ? path : sourcePath,
                    enabled != null && !"false".equalsIgnoreCase(enabled),
                    routeCacheTtlSeconds,
                    detailCacheTtlSeconds,
                    scheduleMinutes,
                    cron,
                    url,
                    label);
            validateSourceRoute(config);
            runtime.routeConfigStore().save(config);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .header("HX-Trigger", "{\"closemodal\": true, \"routeSaved\": true}")
                    .body("");
        } catch (RouteException e) {
            String modal = templates.routeModal(routes(), path, isNew, null, e.getMessage());
            return ResponseEntity.status(HttpStatus.valueOf(e.error().statusCode()))
                    .contentType(MediaType.TEXT_HTML)
                    .body(modal);
        }
    }

    /**
     * Toggles the enabled flag of a route and returns the refreshed category section.
     */
    @PostMapping(value = "/routes/toggle",
                 consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                 produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String toggleRoute(@RequestParam String path,
                              @RequestParam(required = false) String category) {
        RouteConfig cur = runtime.routeConfigStore().get(path);
        runtime.routeConfigStore().save(new RouteConfig(
                cur.path(), cur.sourcePath(), !cur.enabled(),
                cur.routeCacheTtlSeconds(), cur.detailCacheTtlSeconds(),
                cur.scheduleMinutes(), cur.scheduleCron(), cur.feedUrl(), cur.alias()));
        if (category != null && !category.isBlank()) {
            return templates.categorySection(routes(), category);
        }
        return templates.routeTable(routes(), null, null, false);
    }

    /**
     * Triggers an immediate background fetch for a route and returns the refreshed category section.
     */
    @PostMapping(value = "/routes/fetch",
                 consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                 produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String fetchNow(@RequestParam String path, @RequestParam String category) {
        fetchScheduler.triggerFetch(path);
        templates.invalidateHealthCache();
        return templates.categorySection(routes(), category);
    }

    /**
     * Delete a saved route config; refreshes category view if category is supplied.
     */
    @DeleteMapping(value = "/routes", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String deleteRoute(
            @RequestParam String path,
            @RequestParam(required = false) String category) {
        runtime.routeConfigStore().remove(path);
        if (category != null && !category.isBlank()) {
            return templates.categorySection(routes(), category);
        }
        return templates.routeTable(routes(), null, null, false);
    }

    /**
     * Fetch log viewer — paginated entries from fetch_log, optionally filtered by route.
     */
    @GetMapping(value = "/logs", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String logs(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page) {
        int pageSize = 50;
        int total = 0;
        java.util.List<app.store.FetchLogEntry> entries = java.util.Collections.emptyList();
        int todayCount = 0, errorCount = 0;
        if (feedStore != null) {
            try {
                total = feedStore.countLogs(q);
                entries = feedStore.getAllRecentLogs(q, Math.max(0, page) * pageSize, pageSize);
                todayCount = feedStore.countLogsToday();
                errorCount = feedStore.countErrorsToday();
            } catch (Exception e) {
                log.warn("failed to load fetch logs: {}", e.getMessage());
            }
        }
        return templates.logsPage(entries, total, Math.max(0, page), pageSize, q, todayCount, errorCount);
    }

    /**
     * Feed health dashboard — per-route fetch stats from the last 10 runs.
     */
    @GetMapping(value = "/health", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String health() {
        java.util.Map<String, app.store.RouteHealth> healthMap = java.util.Map.of();
        if (feedStore != null) {
            try {
                healthMap = feedStore.loadRouteHealth();
            } catch (Exception e) {
                log.warn("failed to load route health: {}", e.getMessage());
            }
        }
        return templates.healthSection(routes(), healthMap);
    }

    /**
     * AI settings page fragment.
     */
    @GetMapping(value = "/ai", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String aiSettings() {
        return templates.aiSettings(aiConfigStore.get(), aiCounts()[0], aiCounts()[1], null);
    }

    /**
     * Save AI config from form.
     */
    @PostMapping(
            value = "/ai",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String saveAiConfig(
            @RequestParam String host,
            @RequestParam String model,
            @RequestParam(required = false) String enabled) {
        AiConfig config = new AiConfig(host, model, "on".equals(enabled));
        aiConfigStore.save(config);
        return templates.aiSettings(config, aiCounts()[0], aiCounts()[1], "已保存");
    }

    private int[] aiCounts() {
        int extracts = 0, summaries = 0;
        if (feedStore != null) {
            try {
                extracts = feedStore.countExtracts();
            } catch (Exception ignored) {
            }
            try {
                summaries = feedStore.countSummaries();
            } catch (Exception ignored) {
            }
        }
        return new int[]{extracts, summaries};
    }

    /**
     * Test Ollama connectivity — returns an inline status fragment.
     */
    @GetMapping(value = "/ai/test", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String testAi() {
        return templates.aiTestResult(ollamaClient.checkConnection(), aiConfigStore.get().model());
    }

    /**
     * Credentials settings page fragment.
     */
    @GetMapping(value = "/credentials", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String credentialsSettings() {
        return templates.credentialsSettings(runtime.credentialConfigStore().getStored(), null);
    }

    /**
     * Save credentials from form.
     */
    @PostMapping(
            value = "/credentials",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String saveCredentials(
            @RequestParam(defaultValue = "") String bilibiliCookie,
            @RequestParam(defaultValue = "") String twitterCookie,
            @RequestParam(required = false) List<String> uidKey,
            @RequestParam(required = false) List<String> uidCookie,
            @RequestParam(required = false) List<String> uidName) {
        var uidCookieMap = new java.util.LinkedHashMap<String, String>();
        var uidNameMap   = new java.util.LinkedHashMap<String, String>();
        if (uidKey != null && uidCookie != null) {
            int len = Math.min(uidKey.size(), uidCookie.size());
            for (int i = 0; i < len; i++) {
                String k = uidKey.get(i).strip();
                String v = uidCookie.get(i).strip();
                if (!k.isBlank() && !v.isBlank()) {
                    uidCookieMap.put(k, v);
                    if (uidName != null && i < uidName.size()) {
                        String n = uidName.get(i).strip();
                        if (!n.isBlank()) uidNameMap.put(k, n);
                    }
                }
            }
        }
        CredentialConfig saved = runtime.credentialConfigStore().save(
                new CredentialConfig(bilibiliCookie.strip(), uidCookieMap, uidNameMap, twitterCookie.strip()));
        return templates.credentialsSettings(saved, "已保存");
    }

    /**
     * Saves a single per-UID Bilibili cookie from the route modal.
     * Returns an inline status fragment (targets #cookie-save-result).
     */
    @PostMapping(
            value = "/credentials/uid-cookie",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String saveUidCookie(
            @RequestParam String uid,
            @RequestParam(defaultValue = "") String cookie) {
        runtime.credentialConfigStore().putUidCookie(uid.strip(), cookie.strip());
        return "<span class='form-msg ok'>Cookie 已保存</span>";
    }

    /**
     * Lists registered routes and saved mount aliases (JSON API).
     */
    @GetMapping(value = "/api/routes", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<RouteConfig> listRoutes() {
        return runtime.routeConfigStore().list(runtime.routeRegistry().paths());
    }

    /**
     * Persists a route configuration (JSON API).
     */
    @PutMapping(
            value = "/api/routes",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> saveRoute(@RequestBody(required = false) String body) {
        try {
            RouteConfig config = parseRouteConfig(body);
            validateSourceRoute(config);
            return ResponseEntity.ok(runtime.routeConfigStore().save(config));
        } catch (RouteException e) {
            return ResponseEntity.status(HttpStatus.valueOf(e.error().statusCode()))
                    .body(Map.of("error", e.error().message(), "detail", e.getMessage()));
        }
    }

    private RouteConfig parseRouteConfig(String body) {
        if (body == null || body.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "route config body is required");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String path = root.path("path").asText(null);
            JsonNode cronNode = root.path("scheduleCron");
            String scheduleCron = (!cronNode.isMissingNode() && !cronNode.isNull())
                    ? cronNode.asText() : null;
            return new RouteConfig(
                    path,
                    root.path("sourcePath").asText(path),
                    root.path("enabled").asBoolean(true),
                    root.path("routeCacheTtlSeconds").asInt(RouteConfig.DEFAULT_ROUTE_CACHE_TTL_SECONDS),
                    root.path("detailCacheTtlSeconds").asInt(RouteConfig.DEFAULT_DETAIL_CACHE_TTL_SECONDS),
                    root.path("scheduleMinutes").asInt(0),
                    scheduleCron);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.INVALID_PARAMETER,
                    "invalid route config JSON: " + e.getMessage(), e);
        }
    }

    private List<RouteConfig> routes() {
        return runtime.routeConfigStore().list(runtime.routeRegistry().paths());
    }

    private void validateSourceRoute(RouteConfig config) {
        if (!runtime.routeRegistry().paths().contains(config.sourcePath())) {
            throw new RouteException(RouteError.ROUTE_NOT_FOUND);
        }
    }

    /** Builds a `.item-summary-gen` div with an error message and optional retry button. */
    private static String summaryGenDiv(String link, String title, String errorMsg, boolean showRetry) {
        String esc = HtmlUtils.htmlEscape(errorMsg);
        String retryBtn = showRetry ? """
                 <button type="button" class="sum-gen-btn"\
                 hx-post="/index/items/summarize"\
                 hx-target="closest .item-summary-gen"\
                 hx-swap="outerHTML"\
                 hx-include="closest .item-summary-gen">重试</button>""" : "";
        return """
                <div class="item-summary-gen">\
                <input type="hidden" name="link" value="%s">\
                <input type="hidden" name="title" value="%s">\
                <span class="sum-err-text">%s</span>%s\
                </div>""".formatted(
                HtmlUtils.htmlEscape(link),
                HtmlUtils.htmlEscape(title),
                esc, retryBtn);
    }
}
