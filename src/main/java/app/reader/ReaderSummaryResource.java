package app.reader;

import app.ai.AiConfigStore;
import app.ai.ArticleSummarizer;
import app.ai.SummaryProgress;
import app.store.FeedStore;
import java.sql.SQLException;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The reader's summary API: queue an article for an AI summary and poll its progress as JSON.
 */
@Controller
public class ReaderSummaryResource {
    private static final Logger log = LoggerFactory.getLogger(ReaderSummaryResource.class);

    private final FeedStore feedStore;
    private final ArticleSummarizer articleSummarizer;
    private final AiConfigStore aiConfigStore;

    public ReaderSummaryResource(FeedStore feedStore, ArticleSummarizer articleSummarizer, AiConfigStore aiConfigStore) {
        this.feedStore = feedStore;
        this.articleSummarizer = articleSummarizer;
        this.aiConfigStore = aiConfigStore;
    }

    @PostMapping(value = "/reader/summarize",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<SummaryProgress> summarize(
            @RequestParam String link,
            @RequestParam(required = false, defaultValue = "") String title) {
        if (aiConfigStore == null || !aiConfigStore.get().enabled()) {
            return summaryError(400, "AI 功能未启用，请先在后台配置并启用 AI Provider。");
        }
        return summaryResponse(link, true);
    }

    @GetMapping(value = "/reader/summary-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<SummaryProgress> summaryStatus(@RequestParam String link) {
        return summaryResponse(link, false);
    }

    private ResponseEntity<SummaryProgress> summaryResponse(String link, boolean submit) {
        if (link == null || link.isBlank()) return summaryError(400, "缺少文章链接。");
        if (feedStore == null || articleSummarizer == null) return summaryError(503, "文章库暂时不可用。");
        try {
            SummaryProgress progress = submit ? articleSummarizer.request(link) : articleSummarizer.progress(link);
            return ResponseEntity.status(submit && progress.pending() ? 202 : 200)
                    .header("Cache-Control", "no-store").body(progress);
        } catch (NoSuchElementException e) {
            return summaryError(404, "文章尚未保存，无法生成摘要。");
        } catch (IllegalStateException e) {
            return summaryError(503, "摘要服务暂时不可用，请稍后重试。");
        } catch (SQLException e) {
            log.warn("summary request failed: {}", e.toString());
            return summaryError(500, "摘要状态暂时不可用，请稍后重试。");
        }
    }

    private ResponseEntity<SummaryProgress> summaryError(int status, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(new SummaryProgress("error", null, message));
    }
}
