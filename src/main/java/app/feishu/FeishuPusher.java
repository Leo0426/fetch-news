package app.feishu;

import app.core.RouteConfig;
import app.store.FeedStore;
import app.store.StoredItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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
 * Batches advance only after every page succeeds. Retrying a partly sent batch can repeat
 * already delivered pages, providing at-least-once delivery within the retained window.
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
    private final Set<String> routesInFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> digestsInFlight = ConcurrentHashMap.newKeySet();

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
        if (!routesInFlight.add(routePath)) return;

        try {
            Instant until = Instant.now();
            Instant since = lastPushTime.getOrDefault(routePath,
                    until.minus(DEFAULT_LOOKBACK));
            List<StoredItem> items = feedStore.items().loadNewItemsSince(routePath, since).stream()
                    .filter(item -> item.firstSeenAt().isBefore(until))
                    .toList();
            if (items.isEmpty()) return;

            int max = cfg.effectiveMaxItems(category);
            if (doSend("📬 " + routeLabel + " · " + category, category, null, items, webhook, max)) {
                lastPushTime.put(routePath, until);
            }
        } catch (Exception e) {
            log.warn("[feishu] immediate push error for {}: {}", routePath, e.getMessage());
        } finally {
            routesInFlight.remove(routePath);
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
            String cat = categoryByPath.get(r.path());
            if (cat == null) continue;
            FeishuCategoryConfig catCfg = cfg.getCategory(cat);
            if (!catCfg.enabled() || !catCfg.isDigest()) continue;
            digestRoutes.computeIfAbsent(cat, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<RouteConfig>> entry : digestRoutes.entrySet()) {
            String cat = entry.getKey();
            List<RouteConfig> routes = entry.getValue();
            FeishuCategoryConfig catCfg = cfg.getCategory(cat);
            if (!digestsInFlight.add(cat)) continue;

            try {
                if (!isDigestDue(catCfg, cat, now)) continue;
                Instant since = lastDigestTime.getOrDefault(cat, now.minus(DEFAULT_LOOKBACK));
                List<String> paths = routes.stream().map(RouteConfig::path).toList();
                List<StoredItem> items = feedStore.items().loadNewItemsSinceForRoutes(paths, since).stream()
                        .filter(item -> item.firstSeenAt().isBefore(now))
                        .toList();
                if (items.isEmpty()) {
                    lastDigestTime.put(cat, now);
                    continue;
                }

                String webhook = cfg.effectiveWebhook(cat);
                if (webhook.isBlank()) continue;
                int max = cfg.effectiveMaxItems(cat);

                // Build source breakdown: "TechCrunch (5) · Wired (3)"
                Map<String, String> pathToName = routes.stream()
                        .collect(Collectors.toMap(
                                RouteConfig::path, RouteConfig::displayName, (a, b) -> a));
                Map<String, Long> countByPath = items.stream()
                        .collect(Collectors.groupingBy(
                                StoredItem::routePath, Collectors.counting()));
                String sources = countByPath.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(6)
                        .map(e -> "**" + pathToName.getOrDefault(e.getKey(), e.getKey())
                                + "** (" + e.getValue() + ")")
                        .collect(Collectors.joining(" · "));

                if (doSend("📰 " + cat + " 摘要", cat, sources, items, webhook, max)) {
                    lastDigestTime.put(cat, now);
                }
            } catch (Exception e) {
                log.warn("[feishu] digest error for {}: {}", cat, e.getMessage());
            } finally {
                digestsInFlight.remove(cat);
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

    /** Sends a test message and checks both HTTP status and the webhook's business result. */
    public boolean test(String webhookUrl) {
        String now = ZonedDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"));
        Map<String, Object> payload = buildCard(
                "✅ 连接测试成功",
                "turquoise",
                "fetch-news 飞书推送已就绪",
                now,
                List.of());
        try {
            String body = mapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return isSuccessfulResponse(resp);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("[feishu] test error: {}", e.getMessage());
            return false;
        }
    }

    // ── internal send ─────────────────────────────────────────────────────────

    /**
     * Splits items into pages of {@code maxItems} and sends one card per page.
     *
     * @param description optional line shown on the first card only (e.g. source breakdown)
     */
    private boolean doSend(String title, String category, String description,
                        List<StoredItem> items, String webhookUrl, int maxItems) {
        int total = items.size();
        int pages  = (total + maxItems - 1) / maxItems;   // ceil division
        String pushedAt = ZonedDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"));
        String color = categoryColor(category);

        for (int page = 0; page < pages; page++) {
            int from = page * maxItems;
            int to   = Math.min(from + maxItems, total);
            List<StoredItem> batch = items.subList(from, to);

            // Header: append page indicator when sending multiple cards
            String pageTitle = pages > 1
                    ? title + "  (" + (page + 1) + "/" + pages + ")"
                    : title;

            String subtitle = "**推送时间：** " + pushedAt + "　共 **" + total + "** 条";

            StringBuilder sb = new StringBuilder();
            // Source breakdown only on first page
            if (page == 0 && description != null && !description.isBlank()) {
                sb.append("**来源：** ").append(description).append("\n\n");
            }
            for (int i = 0; i < batch.size(); i++) {
                StoredItem item = batch.get(i);
                String text = item.title() != null && !item.title().isBlank()
                        ? item.title() : item.link();
                if (text != null && text.length() > 80) text = text.substring(0, 78) + "…";
                int globalNum = from + i + 1;
                if (item.link() != null && !item.link().isBlank()) {
                    sb.append("**").append(globalNum).append(".** [")
                      .append(text).append("](").append(item.link()).append(")\n");
                } else {
                    sb.append("**").append(globalNum).append(".** ").append(text).append("\n");
                }
                sb.append("　信息获取时间：").append(item.formattedFirstSeen())
                  .append("　新闻发布时间：").append(item.formattedPubDate()).append("\n");
            }

            Map<String, Object> payload = buildCard(
                    pageTitle, color, subtitle, sb.toString().stripTrailing(), null);
            try {
                String body = mapper.writeValueAsString(payload);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (!isSuccessfulResponse(resp)) {
                    log.warn("[feishu] push failed (page {}/{}): HTTP {} — {}",
                            page + 1, pages, resp.statusCode(), resp.body());
                    return false;
                } else {
                    log.info("[feishu] pushed {}/{} items (page {}/{}): {}",
                            to, total, page + 1, pages, title);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("[feishu] send error (page {}/{}): {}", page + 1, pages, e.getMessage());
                return false;
            }
        }
        return true;
    }

    private boolean isSuccessfulResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() != 200) return false;
        JsonNode body = mapper.readTree(response.body());
        if (body == null) return false;
        JsonNode code = body.has("code") ? body.get("code") : body.get("StatusCode");
        return code != null && code.isIntegralNumber() && code.canConvertToInt() && code.intValue() == 0;
    }

    /**
     * Builds a Feishu interactive card payload.
     *
     * @param headerText  card header title
     * @param color       header template color
     * @param subtitle    first line below header (lark_md)
     * @param body        main content (lark_md); null or blank to omit
     * @param extra       extra elements appended after body; null to omit
     */
    private static Map<String, Object> buildCard(String headerText, String color,
                                                  String subtitle, String body,
                                                  List<Map<String, Object>> extra) {
        List<Map<String, Object>> elements = new ArrayList<>();

        if (subtitle != null && !subtitle.isBlank()) {
            elements.add(mdElement(subtitle));
        }
        if (body != null && !body.isBlank()) {
            elements.add(Map.of("tag", "hr"));
            elements.add(mdElement(body));
        }
        if (extra != null) {
            elements.addAll(extra);
        }

        return Map.of(
                "msg_type", "interactive",
                "card", Map.of(
                        "header", Map.of(
                                "title", Map.of("tag", "plain_text", "content", headerText),
                                "template", color),
                        "elements", elements));
    }

    private static Map<String, Object> mdElement(String content) {
        return Map.of("tag", "div",
                "text", Map.of("tag", "lark_md", "content", content));
    }

    private static String categoryColor(String category) {
        if (category == null) return "blue";
        return switch (category) {
            case "时事要闻"                 -> "orange";
            case "财经商业"                 -> "green";
            case "科学", "健康医学"          -> "violet";
            case "人文社会", "设计艺术", "游戏" -> "turquoise";
            case "关注动态"                 -> "turquoise";
            default                       -> "blue";
        };
    }
}
