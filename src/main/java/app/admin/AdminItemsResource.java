package app.admin;

import app.ai.AiConfigStore;
import app.ai.ArticleSummarizer;
import app.ai.SummaryProgress;
import app.store.FeedStore;
import app.store.StoredItem;
import app.store.SummaryStatus;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Admin item browsing in /index: stored articles per route and their AI summary actions.
 */
@Controller
@RequestMapping("/index")
public class AdminItemsResource {
    private static final Logger log = LoggerFactory.getLogger(AdminItemsResource.class);

    private final AdminTemplateRenderer templates;
    private final FeedStore feedStore;
    private final AiConfigStore aiConfigStore;
    private final ArticleSummarizer articleSummarizer;

    public AdminItemsResource(AdminTemplateRenderer templates, FeedStore feedStore, AiConfigStore aiConfigStore,
                              ArticleSummarizer articleSummarizer) {
        this.templates = templates;
        this.feedStore = feedStore;
        this.aiConfigStore = aiConfigStore;
        this.articleSummarizer = articleSummarizer;
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
                total = feedStore.items().countItems(path);
                items = feedStore.items().browseItems(path, Math.max(0, page) * pageSize, pageSize);
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
                summaries = feedStore.aiCache().loadSummaries(links);
                summaryStatuses = feedStore.summaryQueue().statuses(links);
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
