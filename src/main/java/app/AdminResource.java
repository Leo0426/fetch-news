package app;

import app.ai.AiConfig;
import app.ai.AiConfigStore;
import app.ai.AiModelList;
import app.ai.AiProviderForm;
import app.ai.AiSettingsPage;
import app.ai.ArticleSummarizer;
import app.ai.OllamaClient;
import app.ai.SummaryProgress;
import app.core.RouteConfig;
import app.core.RouteError;
import app.core.RouteException;
import app.feishu.FeishuConfig;
import app.feishu.FeishuConfigStore;
import app.feishu.FeishuPusher;
import app.store.FeedRefreshState;
import app.store.FeedStore;
import app.store.StoredItem;
import app.store.SummaryStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

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
    private final FeishuConfigStore feishuConfigStore;
    private final FeishuPusher feishuPusher;

    public AdminResource(AppRuntime runtime, ObjectMapper objectMapper,
                         AdminTemplateRenderer templates, FeedStore feedStore,
                         AiConfigStore aiConfigStore, OllamaClient ollamaClient,
                         ArticleSummarizer articleSummarizer,
                         FetchScheduler fetchScheduler,
                         FeishuConfigStore feishuConfigStore,
                         FeishuPusher feishuPusher) {
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.templates = templates;
        this.feedStore = feedStore;
        this.aiConfigStore = aiConfigStore;
        this.ollamaClient = ollamaClient;
        this.articleSummarizer = articleSummarizer;
        this.fetchScheduler = fetchScheduler;
        this.feishuConfigStore = feishuConfigStore;
        this.feishuPusher = feishuPusher;
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
        Map<String, SummaryStatus> summaryStatuses = Map.of();
        if (feedStore != null && !items.isEmpty()) {
            try {
                List<String> links = items.stream()
                        .filter(i -> i.link() != null).map(StoredItem::link).toList();
                summaries = feedStore.loadSummaries(links);
                summaryStatuses = feedStore.loadSummaryStatuses(links);
            } catch (Exception e) {
                log.warn("failed to load summaries for {}: {}", path, e.getMessage());
            }
        }
        boolean aiEnabled = aiConfigStore != null && aiConfigStore.get().enabled();
        return templates.itemsPanel(path, items, total, Math.max(0, page),
                summaries, summaryStatuses, aiEnabled);
    }

    @PostMapping(
            value = "/items/summarize",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String summarizeItem(
            @RequestParam String link,
            @RequestParam(required = false, defaultValue = "") String title) {
        return summaryResponse(link, true);
    }

    @GetMapping(value = "/items/summary-status", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String summaryStatus(@RequestParam String link) {
        return summaryResponse(link, false);
    }

    private String summaryResponse(String link, boolean submit) {
        if (feedStore == null) return summaryGenDiv(link, "文章存储不可用，请稍后再试。", false);
        if (articleSummarizer == null) return summaryGenDiv(link, "摘要服务暂不可用，请稍后再试。", false);
        try {
            SummaryProgress progress = articleSummarizer.progress(link);
            if (submit && !progress.pending() && !"success".equals(progress.state())) {
                if (!aiEnabled()) return summaryGenDiv(link, "AI 未启用，请先在设置中开启。", false);
                progress = articleSummarizer.request(link);
            }
            return summaryFragment(link, progress);
        } catch (NoSuchElementException e) {
            return summaryGenDiv(link, "未找到已保存的文章，请先抓取后再生成摘要。", false);
        } catch (Exception e) {
            log.warn("admin summary status unavailable for {}: {}", link, e.toString());
            return summaryCheckDiv(link);
        }
    }

    @PostMapping(
            value = "/items/summarize-batch",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String summarizeBatch(@RequestParam String path) {
        if (feedStore == null) return "<span class='sum-status err'>文章存储不可用，请稍后再试。</span>";
        if (!aiEnabled()) return "<span class='sum-status err'>AI 未启用，请先在设置中配置 Ollama。</span>";
        if (articleSummarizer == null) return "<span class='sum-status err'>摘要服务暂不可用，请稍后再试。</span>";
        try {
            int accepted = articleSummarizer.enqueue(feedStore.loadItems(path));
            return accepted == 0
                    ? "<span class='sum-status ok'>没有新增任务；已有摘要或已排队的文章会跳过，失败任务可逐条重试。</span>"
                    : "<span class='sum-status ok'>已加入队列 " + accepted + " 条，将依次生成摘要。</span>";
        } catch (Exception e) {
            log.warn("batch summarize submit failed: {}", e.toString());
            return "<span class='sum-status err'>暂时无法确认提交结果，请刷新文章列表查看任务状态。</span>";
        }
    }

    private boolean aiEnabled() {
        return aiConfigStore != null && aiConfigStore.get().enabled();
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
        List<FeedRefreshState> refreshStates = List.of();
        if (feedStore != null) {
            try {
                healthMap = feedStore.loadRouteHealth();
            } catch (Exception e) {
                log.warn("failed to load route health: {}", e.getMessage());
            }
            try {
                refreshStates = feedStore.loadFeedRefreshStates();
            } catch (Exception e) {
                log.warn("failed to load RSS refresh state: {}", e.getMessage());
            }
        }
        return templates.healthSection(routes(), healthMap, refreshStates);
    }

    /**
     * AI settings page fragment.
     */
    @GetMapping(value = "/ai", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> aiSettings(@RequestParam(required = false) String profileId) {
        try { return ResponseEntity.ok(renderAiSettings(profileId, null)); }
        catch (NoSuchElementException e) { return ResponseEntity.status(404).body("AI platform not found"); }
    }

    @PostMapping(value = "/ai", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> saveAiConfig(
            @ModelAttribute AiProviderForm form,
            @RequestParam(required = false) List<String> modelIds,
            @RequestParam(defaultValue = "false") boolean activate) {
        try {
            String id=aiConfigStore.saveProfile(form.profileId(),form.name(),form.resolve(aiConfigStore),activate,modelIds);
            return ResponseEntity.ok(renderAiSettings(id,activate ? "已保存并启用此提供商" : "已保存提供商配置"));
        } catch(IllegalArgumentException | NoSuchElementException e) { return aiFormError(e.getMessage(),400); }
        catch(java.io.UncheckedIOException e) { return aiFormError("保存失败，原配置未改变",500); }
    }

    @PostMapping(value = "/ai/activate", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> activateAi(@RequestParam String profileId) {
        try { aiConfigStore.activate(profileId); return ResponseEntity.ok(renderAiSettings(profileId,"已切换当前平台")); }
        catch(IllegalArgumentException | NoSuchElementException e) { return aiFormError("平台不存在",400); }
        catch(java.io.UncheckedIOException e) { return aiFormError("保存失败，原配置未改变",500); }
    }

    @PostMapping(value = "/ai/enabled", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> enableAi(@RequestParam(defaultValue = "false") boolean enabled) {
        try {
            aiConfigStore.setEnabled(enabled);
            return ResponseEntity.ok(renderAiSettings(null, enabled ? "已开启 AI" : "已关闭 AI"));
        } catch (java.io.UncheckedIOException e) { return aiFormError("保存失败，原配置未改变", 500); }
    }

    @PostMapping(value = "/ai/delete", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> deleteAi(@RequestParam String profileId) {
        try { aiConfigStore.deleteProfile(profileId); return ResponseEntity.ok(renderAiSettings(null,"已删除平台配置")); }
        catch(IllegalArgumentException | NoSuchElementException e) { return aiFormError("请先切换到其他平台，再删除此配置",400); }
        catch(java.io.UncheckedIOException e) { return aiFormError("保存失败，原配置未改变",500); }
    }

    private String renderAiSettings(String selectedId,String message) {
        int[] counts=aiCounts();
        return templates.aiSettings(AiSettingsPage.from(aiConfigStore,selectedId),counts[0],counts[1],message);
    }

    private ResponseEntity<String> aiFormError(String message,int status) {
        return ResponseEntity.status(status).body("<div class=\"form-msg err\">"+HtmlUtils.htmlEscape(message)+"</div>");
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
     * Test the active AI platform connectivity — returns an inline status fragment.
     */
    @GetMapping(value = "/ai/test", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String testAi() {
        return templates.aiTestResult(ollamaClient.checkConnection(), aiConfigStore.get().model());
    }

    @PostMapping(value = "/ai/test", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> testAiProvider(@ModelAttribute AiProviderForm form) {
        try {
            AiConfig config = form.resolve(aiConfigStore);
            return ResponseEntity.ok(templates.aiTestResult(ollamaClient.checkConnection(config), config.model()));
        } catch (IllegalArgumentException | NoSuchElementException e) { return aiFormError(e.getMessage(), 400); }
    }

    @PostMapping(value = "/ai/models", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<AiModelList> aiModels(@ModelAttribute AiProviderForm form) {
        try {
            return ResponseEntity.ok(AiModelList.from(ollamaClient.checkConnection(form.resolveForDiscovery(aiConfigStore))));
        } catch (IllegalArgumentException | NoSuchElementException e) {
            return ResponseEntity.badRequest().body(new AiModelList(List.of(), e.getMessage()));
        }
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
     * Feishu push settings page fragment.
     */
    @GetMapping(value = "/feishu", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String feishuSettings() {
        return templates.feishuSettings(feishuConfigStore.get(), allCategories(), null);
    }

    /**
     * Save global Feishu config (webhook URL, enabled flag, default max items).
     * Preserves existing per-category configs.
     */
    @PostMapping(
            value = "/feishu",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String saveFeishuConfig(
            @RequestParam(defaultValue = "") String webhookUrl,
            @RequestParam(required = false) String enabled,
            @RequestParam(defaultValue = "10") int maxItemsPerPush) {
        FeishuConfig current = feishuConfigStore.get();
        FeishuConfig config = new FeishuConfig(
                webhookUrl.strip(),
                "on".equals(enabled),
                maxItemsPerPush,
                current.categories());
        feishuConfigStore.save(config);
        return templates.feishuSettings(config, allCategories(), "已保存");
    }

    /**
     * Save config for a single category. Merges into the existing categories map.
     */
    @PostMapping(
            value = "/feishu/category",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String saveFeishuCategory(
            @RequestParam String categoryName,
            @RequestParam(required = false) String enabled,
            @RequestParam(defaultValue = "immediate") String mode,
            @RequestParam(defaultValue = "") String webhookUrl,
            @RequestParam(defaultValue = "") String digestCron,
            @RequestParam(defaultValue = "0") int maxItemsPerPush) {
        FeishuConfig current = feishuConfigStore.get();
        var categories = new java.util.LinkedHashMap<>(current.categories());
        categories.put(categoryName, new app.feishu.FeishuCategoryConfig(
                "on".equals(enabled),
                webhookUrl.strip(),
                mode,
                digestCron.strip(),
                maxItemsPerPush));
        feishuConfigStore.save(new FeishuConfig(
                current.webhookUrl(), current.enabled(), current.maxItemsPerPush(), categories));
        return "<span class='form-msg ok'>已保存</span>";
    }

    /**
     * Tests Feishu webhook connectivity — returns an inline status fragment.
     */
    @PostMapping(
            value = "/feishu/test",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String testFeishu(@RequestParam(defaultValue = "") String webhookUrl) {
        if (webhookUrl.isBlank()) {
            return "<span class='form-msg err'>请先填写 Webhook URL</span>";
        }
        boolean ok = feishuPusher.test(webhookUrl.strip());
        return ok
                ? "<span class='form-msg ok'>连接成功，请查收飞书测试消息</span>"
                : "<span class='form-msg err'>连接失败，请检查 Webhook URL 是否正确</span>";
    }

    private List<String> allCategories() {
        return runtime.routeRegistry().categories().values()
                .stream().distinct().sorted().toList();
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

    private String summaryFragment(String link, SummaryProgress progress) {
        if ("success".equals(progress.state())) {
            return "<div class=\"item-summary-gen\"><div class=\"item-summary\">"
                    + HtmlUtils.htmlEscape(progress.summary())
                    + "</div><div class=\"item-summary-meta\" role=\"status\">摘要完成</div></div>";
        }
        if (progress.pending()) {
            boolean queued = "queued".equals(progress.state());
            String hint = queued && !aiEnabled() ? "AI 已关闭，任务保留在队列中。" : "可以离开此页，完成后自动显示摘要。";
            return """
                    <div class="item-summary-gen" hx-get="%s" hx-trigger="every 2s" hx-target="this" hx-swap="outerHTML">
                    <div class="item-summary-meta running" role="status" aria-live="polite">%s</div>
                    <p class="item-summary-hint">%s</p></div>
                    """.formatted(summaryStatusUrl(link), queued ? "排队中" : "摘要中", hint);
        }
        String error = "error".equals(progress.state()) ? progress.error() : null;
        if (!aiEnabled() && error == null) error = "AI 未启用，请先在设置中开启。";
        return summaryGenDiv(link, error, aiEnabled());
    }

    private static String summaryStatusUrl(String link) {
        return HtmlUtils.htmlEscape(UriComponentsBuilder.fromPath("/index/items/summary-status")
                .queryParam("link", "{link}").encode().buildAndExpand(Map.of("link", link)).toUriString());
    }

    private static String summaryCheckDiv(String link) {
        return """
                <div class="item-summary-gen">
                <p class="item-summary-error" role="alert">摘要状态暂时无法确认，请重新查看状态。</p>
                <button type="button" class="sum-gen-btn" hx-get="%s"
                hx-target="closest .item-summary-gen" hx-swap="outerHTML" hx-disabled-elt="this">重新查看状态</button>
                </div>
                """.formatted(summaryStatusUrl(link));
    }

    private static String summaryGenDiv(String link, String error, boolean showButton) {
        StringBuilder html = new StringBuilder("<div class=\"item-summary-gen\">");
        if (error != null) {
            html.append("<p class=\"item-summary-error\" role=\"alert\">")
                    .append(HtmlUtils.htmlEscape(error)).append("</p>");
        }
        if (showButton) {
            html.append("<input type=\"hidden\" name=\"link\" value=\"")
                    .append(HtmlUtils.htmlEscape(link)).append("\">")
                    .append("""
                            <button type="button" class="sum-gen-btn" hx-post="/index/items/summarize"
                            hx-target="closest .item-summary-gen" hx-swap="outerHTML"
                            hx-include="closest .item-summary-gen" hx-disabled-elt="this">
                            """).append(error == null ? "✦ 生成摘要" : "✦ 重试生成").append("</button>");
        }
        return html.append("</div>").toString();
    }
}
