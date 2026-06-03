package app.ai;

import app.core.FeedItem;
import app.store.FeedStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enriches feed items that lack a description by fetching the article HTML,
 * extracting the main text with JSoup, then summarising with Ollama.
 *
 * <p>Results are persisted in SQLite so each URL is only processed once.
 */
public class ArticleExtractor {
    private static final Logger log = LoggerFactory.getLogger(ArticleExtractor.class);

    /** Items whose description is shorter than this are candidates for enrichment. */
    private static final int MIN_DESC_LENGTH = 200;
    /** Maximum characters of extracted page text sent to Ollama. */
    private static final int MAX_INPUT_CHARS = 4000;

    private final OllamaClient ollama;
    private final FeedStore feedStore;
    private final HttpClient http;

    public ArticleExtractor(OllamaClient ollama, FeedStore feedStore) {
        this.ollama = ollama;
        this.feedStore = feedStore;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Returns a new list of feed items where every item with a short or absent
     * description has been enriched with AI-extracted content.
     * Items that already have sufficient content are returned unchanged.
     * Failures are logged and the original item is returned as a fallback.
     *
     * @param items source feed items
     * @return enriched feed items in the same order
     */
    public List<FeedItem> enrich(List<FeedItem> items) {
        List<FeedItem> result = new ArrayList<>(items.size());
        for (FeedItem item : items) {
            result.add(needsEnrichment(item) ? enrichOne(item) : item);
        }
        return result;
    }

    private boolean needsEnrichment(FeedItem item) {
        if (item.link() == null || item.link().isBlank()) return false;
        String desc = item.description();
        return desc == null || desc.strip().length() < MIN_DESC_LENGTH;
    }

    private FeedItem enrichOne(FeedItem item) {
        try {
            String cached = feedStore.loadExtract(item.link());
            if (cached != null) {
                return withDescription(item, cached);
            }
            String html = fetchHtml(item.link());
            if (html == null) return item;
            String text = extractText(html);
            if (text.isBlank()) return item;
            String input = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
            String extracted = ollama.generate(buildPrompt(item.title(), input)).strip();
            if (extracted.isBlank()) return item;
            feedStore.saveExtract(item.link(), extracted, ollama.model());
            return withDescription(item, extracted);
        } catch (Exception e) {
            log.warn("AI enrichment failed for {}: {}", item.link(), e.getMessage());
            return item;
        }
    }

    private String fetchHtml(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 (compatible; fetch-news/1.0)")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return (response.statusCode() >= 200 && response.statusCode() < 300) ? response.body() : null;
        } catch (Exception e) {
            log.debug("failed to fetch article {}: {}", url, e.getMessage());
            return null;
        }
    }

    private static String extractText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script,style,nav,header,footer,aside,iframe,.sidebar,.ad,.advertisement,.cookie-banner").remove();
        // Try semantic / common article containers first
        for (String selector : new String[]{
                "article", "main", "[role=main]",
                ".article-body", ".post-content", ".entry-content",
                ".article__body", ".story-body", ".content-body"}) {
            Element el = doc.selectFirst(selector);
            if (el != null) {
                String text = el.text();
                if (text.length() > 200) return text;
            }
        }
        return doc.body() != null ? doc.body().text() : "";
    }

    private static String buildPrompt(String title, String content) {
        return """
                Extract the main article content from the text below. Remove navigation menus, \
                advertisements, author bios, related-article links, and other non-article text. \
                Return only the core article body in clean readable format. \
                Preserve the original language — do not translate. Limit to 600 words.

                Title: %s

                Content:
                %s
                """.formatted(title != null ? title : "", content);
    }

    private static FeedItem withDescription(FeedItem item, String description) {
        return new FeedItem(
                item.title(), item.link(), description,
                item.pubDate(), item.author(), item.categories());
    }
}
