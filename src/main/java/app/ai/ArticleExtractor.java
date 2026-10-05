package app.ai;

import app.core.FeedItem;
import app.core.FetchClient;
import app.store.FeedStore;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class ArticleExtractor {
    private final OllamaClient ollama;
    private final FeedStore feedStore;
    private final FetchClient client;

    public ArticleExtractor(OllamaClient ollama, FeedStore feedStore, FetchClient client) {
        this.ollama = ollama;
        this.feedStore = feedStore;
        this.client = client;
    }

    public String extract(FeedItem item) throws Exception {
        if (feedStore == null) throw new IllegalStateException("Article storage unavailable");
        String cached = feedStore.loadExtract(item.link());
        if (cached != null && !cached.isBlank()) return cached;
        String text = extractText(client.get(item.link()));
        if (text.isBlank()) throw new IllegalStateException("No article content available");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Article extraction interrupted");
        OllamaClient.Generation generation = ollama.generateWithMetadata(buildPrompt(item.title(), truncate(text, 4000)));
        String extracted = generation.text() == null ? "" : generation.text().strip();
        if (extracted.isBlank()) throw new IllegalStateException("Ollama returned empty article content");
        feedStore.saveExtract(item.link(), extracted, generation.model());
        return extracted;
    }

    static String truncate(String text, int limit) {
        int length = text.codePointCount(0, text.length());
        return length <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }

    private static String extractText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script,style,nav,header,footer,aside,iframe,.sidebar,.ad,.advertisement,.cookie-banner").remove();
        for (String selector : new String[]{"article", "main", "[role=main]", ".article-body", ".post-content",
                ".entry-content", ".article__body", ".story-body", ".content-body"}) {
            Element element = doc.selectFirst(selector);
            if (element != null && element.text().length() > 200) return element.text();
        }
        return doc.body() == null ? "" : doc.body().text();
    }

    private static String buildPrompt(String title, String content) {
        return """
                Extract the main article content from the text below. Remove navigation menus,
                advertisements, author bios, related-article links, and other non-article text.
                Return only the core article body in clean readable format.
                Preserve the original language — do not translate. Limit to 600 words.

                Title: %s

                Content:
                %s
                """.formatted(title == null ? "" : title, content);
    }
}
