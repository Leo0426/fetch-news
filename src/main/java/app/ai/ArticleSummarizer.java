package app.ai;

import app.core.FeedItem;
import app.store.FeedStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates concise 2-3 sentence Chinese summaries for feed items using Ollama.
 *
 * <p>Input priority for each item:
 * <ol>
 *   <li>Cached full-text from {@code ai_extracts} (no re-fetch needed)</li>
 *   <li>Existing {@code description} when it is substantial (&ge;300 chars)</li>
 *   <li>Fetch article HTML and extract main text via JSoup</li>
 * </ol>
 *
 * <p>Results are persisted in {@code ai_summaries} so Ollama is called at most once per URL.
 */
public class ArticleSummarizer {
    private static final Logger log = LoggerFactory.getLogger(ArticleSummarizer.class);

    private static final int MIN_DESC_FOR_SUMMARY = 300;
    private static final int MAX_INPUT_CHARS = 3000;

    private final OllamaClient ollama;
    private final FeedStore feedStore;
    private final HttpClient http;

    public ArticleSummarizer(OllamaClient ollama, FeedStore feedStore) {
        this.ollama = ollama;
        this.feedStore = feedStore;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Generates and persists summaries for items that do not already have one.
     * Called after each scheduled fetch.
     *
     * @param items feed items from the most recent fetch
     */
    public void summarize(List<FeedItem> items) {
        for (FeedItem item : items) {
            if (item.link() == null || item.link().isBlank()) continue;
            try {
                if (feedStore.loadSummary(item.link()) != null) continue;
                String input = resolveInput(item);
                if (input == null || input.isBlank()) continue;
                String truncated = input.length() > MAX_INPUT_CHARS
                        ? input.substring(0, MAX_INPUT_CHARS) : input;
                String summary = ollama.generate(buildPrompt(item.title(), truncated)).strip();
                if (!summary.isBlank()) {
                    feedStore.saveSummary(item.link(), summary, ollama.model());
                }
            } catch (Exception e) {
                log.warn("summarization failed for {}: {}", item.link(), e.getMessage());
            }
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String resolveInput(FeedItem item) {
        try {
            // 1. Use cached full-text extract if available
            String extract = feedStore.loadExtract(item.link());
            if (extract != null && !extract.isBlank()) return extract;
        } catch (Exception e) {
            log.debug("failed to load extract for {}: {}", item.link(), e.getMessage());
        }

        // 2. Use existing description when it's substantial enough
        String desc = item.description();
        if (desc != null && desc.strip().length() >= MIN_DESC_FOR_SUMMARY) {
            return Jsoup.parse(desc).text();
        }

        // 3. Fetch article and extract main text
        return fetchAndExtract(item.link());
    }

    private String fetchAndExtract(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 (compatible; fetch-news/1.0)")
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
            return extractText(resp.body());
        } catch (Exception e) {
            log.debug("fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private static String extractText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script,style,nav,header,footer,aside,iframe,.ad,.sidebar").remove();
        for (String sel : new String[]{
                "article", "main", "[role=main]",
                ".article-body", ".post-content", ".entry-content", ".content-body"}) {
            Element el = doc.selectFirst(sel);
            if (el != null) {
                String text = el.text();
                if (text.length() > 200) return text;
            }
        }
        return doc.body() != null ? doc.body().text() : "";
    }

    private static String buildPrompt(String title, String content) {
        return """
                你是一位新闻编辑。请用简体中文为以下文章写 2-3 句摘要，抓住核心事实和关键观点，简洁客观。\
                不要以"本文"或"这篇文章"开头，直接陈述内容。

                标题：%s

                内容：
                %s
                """.formatted(title != null ? title : "", content);
    }
}
