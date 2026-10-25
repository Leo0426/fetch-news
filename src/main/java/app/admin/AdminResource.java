package app.admin;

import app.AppRuntime;
import app.core.CredentialConfig;
import app.core.RouteConfig;
import app.core.RouteError;
import app.core.RouteException;
import app.fetch.FetchScheduler;
import app.store.FeedRefreshState;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
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
    private final FetchScheduler fetchScheduler;

    public AdminResource(AppRuntime runtime, ObjectMapper objectMapper,
                         AdminTemplateRenderer templates, FeedStore feedStore,
                         FetchScheduler fetchScheduler) {
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.templates = templates;
        this.feedStore = feedStore;
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
            @RequestParam(defaultValue = "false") boolean autoSummary,
            @RequestParam(required = false) String category,
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
                    label,
                    autoSummary,
                    category);
            if (config.category() != null && !runtime.topics().contains(config.category())) {
                throw new RouteException(RouteError.INVALID_PARAMETER, "未知的分类：" + config.category());
            }
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
    public ResponseEntity<String> toggleRoute(@RequestParam String path,
                                              @RequestParam(required = false) String category) {
        RouteConfig cur = runtime.routeConfigStore().get(path);
        if (runtime.isTemplate(cur)) return templateRouteRejected();
        runtime.routeConfigStore().save(cur.withEnabled(!cur.enabled()));
        if (category != null && !category.isBlank()) {
            return ResponseEntity.ok(templates.categorySection(routes(), category));
        }
        return ResponseEntity.ok(templates.routeTable(routes(), null, null, false));
    }

    /**
     * Triggers an immediate background fetch for a route and returns the refreshed category section.
     */
    @PostMapping(value = "/routes/fetch",
                 consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                 produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> fetchNow(@RequestParam String path, @RequestParam String category) {
        if (runtime.isTemplate(runtime.routeConfigStore().get(path))) return templateRouteRejected();
        fetchScheduler.triggerFetch(path);
        templates.invalidateHealthCache();
        return ResponseEntity.ok(templates.categorySection(routes(), category));
    }

    private static ResponseEntity<String> templateRouteRejected() {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.TEXT_HTML)
                .body("<span class='form-msg err'>模板路由不能直接开关或抓取，请在它的实例上操作。</span>");
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
        List<app.store.FetchLogEntry> entries = Collections.emptyList();
        int todayCount = 0, errorCount = 0;
        if (feedStore != null) {
            try {
                total = feedStore.fetchState().countLogs(q);
                entries = feedStore.fetchState().getAllRecentLogs(q, Math.max(0, page) * pageSize, pageSize);
                todayCount = feedStore.fetchState().countLogsToday();
                errorCount = feedStore.fetchState().countErrorsToday();
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
        Map<String, app.store.RouteHealth> healthMap = Map.of();
        List<FeedRefreshState> refreshStates = List.of();
        if (feedStore != null) {
            try {
                healthMap = feedStore.fetchState().loadRouteHealth();
            } catch (Exception e) {
                log.warn("failed to load route health: {}", e.getMessage());
            }
            try {
                refreshStates = feedStore.fetchState().loadFeedRefreshStates();
            } catch (Exception e) {
                log.warn("failed to load RSS refresh state: {}", e.getMessage());
            }
        }
        return templates.healthSection(routes(), healthMap, refreshStates);
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
        var uidCookieMap = new LinkedHashMap<String, String>();
        var uidNameMap   = new LinkedHashMap<String, String>();
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

}
