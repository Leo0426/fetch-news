package app.ai;

import app.core.FeedItem;
import app.core.FetchClient;
import app.store.FeedStore;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

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
        ArticleText articleText = extractText(client.get(item.link()));
        String text = truncate(articleText.text(), 4000);
        if (text.isBlank()) throw new IllegalStateException("No article content available");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Article extraction interrupted");
        if (articleText.mainContent()) {
            feedStore.saveExtract(item.link(), text, "html");
            return text;
        }
        OllamaClient.Generation generation = ollama.generateWithMetadata(buildPrompt(item.title(), text));
        String extracted = generation.text() == null ? "" : generation.text().strip();
        if (extracted.isBlank()) throw new IllegalStateException("Ollama returned empty article content");
        feedStore.saveExtract(item.link(), extracted, generation.model());
        return extracted;
    }

    static String truncate(String text, int limit) {
        int length = text.codePointCount(0, text.length());
        return length <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }

    private static ArticleText extractText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script,style,nav,header,footer,aside,iframe,button,[role=button],[role=navigation],"
                + ".sidebar,.ad,.advertisement,.cookie-banner").remove();
        for (String selector : new String[]{"[itemprop=articleBody]", ".article-body", ".post-content",
                ".entry-content", ".article__body", ".story-body", ".content-body", "article", "main", "[role=main]"}) {
            Element element = doc.selectFirst(selector);
            if (element == null) continue;
            String text = element.text();
            if (text.codePointCount(0, text.length()) > 200) return new ArticleText(readableText(element), true);
        }
        return new ArticleText(doc.body() == null ? "" : doc.body().text(), false);
    }

    private static String readableText(Element element) {
        element.getAllElements().stream().filter(node -> node != element && node.tag().isBlock()
                        && !node.tagName().equals("br"))
                .forEach(node -> node.after(new TextNode("\n\n")));
        element.select("br").forEach(node -> node.replaceWith(new TextNode("\n")));
        return element.wholeText().replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Zs}\\t]+", " ").replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n").strip();
    }

    private record ArticleText(String text, boolean mainContent) {}

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
