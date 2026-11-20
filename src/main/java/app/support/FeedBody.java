package app.support;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

public final class FeedBody {
    private FeedBody() {}

    /** Some archived feeds appended an entity-escaped link back to the article after the source excerpt. */
    public static String forReading(String html, String articleUrl) {
        String body = HtmlImages.unwrapBody(html);
        if (articleUrl == null || articleUrl.isBlank() || !body.endsWith("&lt;/a&gt;")) return body;
        int start = body.lastIndexOf("&lt;a ");
        if (start < 0) return body;
        String candidate = Parser.unescapeEntities(body.substring(start), false);
        var fragment = Jsoup.parseBodyFragment(candidate, articleUrl).body();
        var link = fragment.firstElementChild();
        if (fragment.childNodeSize() != 1 || link == null || !link.normalName().equals("a")
                || link.childrenSize() != 0 || !articleUrl.equals(link.absUrl("href"))) return body;
        String label = link.text();
        return label.equals("查看全文") || label.equals("阅读全文") ? body.substring(0, start).stripTrailing() : body;
    }
}
