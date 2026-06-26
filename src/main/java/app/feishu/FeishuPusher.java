package app.feishu;

import app.core.RouteConfig;
import app.store.FeedStore;
import app.store.StoredItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

/**
 * Sends feed items to Feishu (Lark) custom bot webhooks.
 *
 * <p>Supports two push modes per category:
 * <ul>
 *   <li><b>immediate</b> — pushes new items after each scheduled route fetch</li>
 *   <li><b>digest</b>    — batches items across a category and pushes on a cron schedule</li>
 * </ul>
 *
 * <p>Last-push timestamps are kept in memory; on restart, the default lookback window
 * ({@link #DEFAULT_LOOKBACK}) applies so the first push after startup covers recent content.
 */
public class FeishuPusher {
    private static final Logger log = LoggerFactory.getLogger(FeishuPusher.class);
    private static final Duration DEFAULT_LOOKBACK = Duration.ofHours(24);

    private final FeishuConfigStore configStore;
    private final ObjectMapper mapper;
    private final HttpClient http;

    /** Last push time per route path — for immediate mode. */
    private final ConcurrentHashMap<String, Instant> lastPushTime = new ConcurrentHashMap<>();
    /** Last digest time per category name — for digest mode. */
    private final ConcurrentHashMap<String, Instant> lastDigestTime = new ConcurrentHashMap<>();

    public FeishuPusher(FeishuConfigStore configStore, ObjectMapper mapper) {
        this.configStore = configStore;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── immediate mode ────────────────────────────────────────────────────────

    /**
     * Called after each scheduled route fetch.
     * Pushes items first seen for {@code routePath} since the last push, if the category
     * is configured for immediate mode.
     *
     * @param routePath  canonical route path (e.g. {@code /hn/top})
     * @param category   category this route belongs to
     * @param routeLabel display name used as the message title
     * @param feedStore  store to query new items from
     */
    public void pushRouteItems(String routePath, String category,
                               String routeLabel, FeedStore feedStore) {
        FeishuConfig cfg = configStore.get();
        if (!cfg.enabled()) return;
        FeishuCategoryConfig catCfg = cfg.getCategory(category);
        if (!catCfg.enabled() || !catCfg.isImmediate()) return;

        String webhook = cfg.effectiveWebhook(category);
        if (webhook.isBlank()) return;

        try {
            Instant since = lastPushTime.getOrDefault(routePath,
                    Instant.now().minus(DEFAULT_LOOKBACK));
            List<StoredItem> items = feedStore.loadNewItemsSince(routePath, since);
            if (items.isEmpty()) return;

            int max = cfg.effectiveMaxItems(category);
            doSend("【fetch-news】" + routeLabel + " 新增 " + items.size() + " 条",
                    items, webhook, max);
            lastPushTime.put(routePath, Instant.now());
        } catch (Exception e) {
            log.warn("[feishu] immediate push error for {}: {}", routePath, e.getMessage());
        }
    }

    // ── digest mode ───────────────────────────────────────────────────────────

    /**
     * Called every scheduler tick. Checks each category configured for digest mode;
     * when the cron fires, collects all new items across the category's routes and pushes
     * them as a single batched message.
     *
     * @param now            current tick time
     * @param allRoutes      all configured routes (enabled or not)
     * @param categoryByPath map of route sourcePath → category name
     * @param feedStore      store to query items from
     */
    public void checkDigests(Instant now, List<RouteConfig> allRoutes,
                             Map<String, String> categoryByPath, FeedStore feedStore) {
        FeishuConfig cfg = configStore.get();
        if (!cfg.enabled()) return;

        // Group enabled, concrete routes by category for digest categories
        Map<String, List<RouteConfig>> digestRoutes = new LinkedHashMap<>();
        for (RouteConfig r : allRoutes) {
            if (r.path().contains(":")) continue;   // skip parameterized patterns
            String cat = categoryByPath.get(r.sourcePath());
            if (cat == null) continue;
            FeishuCategoryConfig catCfg = cfg.getCategory(cat);
            if (!catCfg.enabled() || !catCfg.isDigest()) continue;
            digestRoutes.computeIfAbsent(cat, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<RouteConfig>> entry : digestRoutes.entrySet()) {
            String cat = entry.getKey();
            List<RouteConfig> routes = entry.getValue();
            FeishuCategoryConfig catCfg = cfg.getCategory(cat);
            if (!isDigestDue(catCfg, cat, now)) continue;

            Instant since = lastDigestTime.getOrDefault(cat, now.minus(DEFAULT_LOOKBACK));
            lastDigestTime.put(cat, now);   // always advance to avoid repeated triggers

            try {
                List<String> paths = routes.stream().map(RouteConfig::path).toList();
                List<StoredItem> items = feedStore.loadNewItemsSinceForRoutes(paths, since);
                if (items.isEmpty()) continue;

                String webhook = cfg.effectiveWebhook(cat);
                if (webhook.isBlank()) continue;
                int max = cfg.effectiveMaxItems(cat);
                doSend("【摘要】" + cat, items, webhook, max);
            } catch (Exception e) {
                log.warn("[feishu] digest error for {}: {}", cat, e.getMessage());
            }
        }
    }

    private boolean isDigestDue(FeishuCategoryConfig catCfg, String cat, Instant now) {
        if (catCfg.digestCron() == null || catCfg.digestCron().isBlank()) return false;
        try {
            Instant last = lastDigestTime.getOrDefault(cat, Instant.EPOCH);
            ZonedDateTime lastZdt = last.atZone(ZoneId.systemDefault());
            ZonedDateTime nextRun = CronExpression.parse(catCfg.digestCron()).next(lastZdt);
            return nextRun != null && !nextRun.toInstant().isAfter(now);
        } catch (Exception e) {
            log.warn("[feishu] invalid digest cron for {}: {}", cat, e.getMessage());
            return false;
        }
    }

    // ── connectivity test ─────────────────────────────────────────────────────

    /** Sends a test message directly to {@code webhookUrl}. Returns true on HTTP 200. */
    public boolean test(String webhookUrl) {
        Map<String, Object> payload = Map.of(
                "msg_type", "text",
                "content", Map.of("text", "✅ fetch-news 飞书推送连接测试成功"));
        try {
            String body = mapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.warn("[feishu] test error: {}", e.getMessage());
            return false;
        }
    }

    // ── internal send ─────────────────────────────────────────────────────────

    private void doSend(String title, List<StoredItem> items, String webhookUrl, int maxItems) {
        List<StoredItem> toSend = items.size() > maxItems ? items.subList(0, maxItems) : items;
        List<List<Map<String, String>>> content = new ArrayList<>();
        for (StoredItem item : toSend) {
            String text = item.title() != null && !item.title().isBlank() ? item.title() : item.link();
            if (item.link() != null && !item.link().isBlank()) {
                content.add(List.of(Map.of("tag", "a", "text", text, "href", item.link())));
            } else {
                content.add(List.of(Map.of("tag", "text", "text", text)));
            }
        }
        if (items.size() > maxItems) {
            content.add(List.of(Map.of("tag", "text",
                    "text", "… 另有 " + (items.size() - maxItems) + " 条未展示")));
        }
        Map<String, Object> payload = Map.of(
                "msg_type", "post",
                "content", Map.of(
                        "post", Map.of(
                                "zh_cn", Map.of("title", title, "content", content))));
        try {
            String body = mapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[feishu] push failed: HTTP {} — {}", resp.statusCode(), resp.body());
            } else {
                log.info("[feishu] pushed {} items: {}", toSend.size(), title);
            }
        } catch (Exception e) {
            log.warn("[feishu] send error: {}", e.getMessage());
        }
    }
}
