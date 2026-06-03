package app.support;

import app.core.Feed;
import app.core.FeedItem;
import app.core.RouteError;
import app.core.RouteException;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * Parses upstream RSS 2.0 and Atom feeds into the normalized Feed model.
 */
public final class FeedParser {

    private FeedParser() {}

    /**
     * Parses an RSS 2.0 or Atom XML document into a normalized feed.
     *
     * @param xml     raw feed XML
     * @param baseUrl source URL, used to resolve relative links
     * @return normalized feed
     * @throws RouteException when the document is not a recognized feed format
     */
    public static Feed parse(String xml, String baseUrl) {
        Document doc = Jsoup.parse(xml, baseUrl, Parser.xmlParser());
        if (doc.selectFirst("channel") != null) {
            return parseRss(doc, baseUrl);
        }
        if (doc.selectFirst("feed") != null) {
            return parseAtom(doc, baseUrl);
        }
        throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT, "not a recognized RSS or Atom feed");
    }

    // ── RSS 2.0 ──────────────────────────────────────────────────────────────

    private static Feed parseRss(Document doc, String baseUrl) {
        Element channel = doc.selectFirst("channel");
        String title = text(channel.selectFirst("title"));
        String link  = text(channel.selectFirst("link"));
        String desc  = text(channel.selectFirst("description"));

        List<FeedItem> items = channel.select("item").stream()
                .map(item -> parseRssItem(item, baseUrl))
                .filter(i -> i.title() != null || i.link() != null)
                .toList();

        return new Feed(
                title != null ? title : "(feed)",
                link  != null && !link.isBlank() ? link : baseUrl,
                desc  != null ? desc : "",
                items);
    }

    private static FeedItem parseRssItem(Element item, String baseUrl) {
        String title = text(item.selectFirst("title"));
        String link  = text(item.selectFirst("link"));
        if (link == null || link.isBlank()) {
            Element guid = item.selectFirst("guid");
            if (guid != null && !"false".equalsIgnoreCase(guid.attr("isPermaLink"))) {
                link = guid.text().strip();
            }
        }
        String desc    = inner(item.selectFirst("description"));
        String pubDate = text(item.selectFirst("pubDate"));
        String author  = text(item.selectFirst("author"));
        if (author == null) {
            // dc:creator is commonly used; JSoup XML mode stores it as "creator"
            author = text(item.selectFirst("creator"));
        }
        List<String> cats = item.select("category").stream()
                .map(Element::text).filter(t -> !t.isBlank()).toList();

        return new FeedItem(
                title,
                link != null && !link.isBlank() ? Urls.resolve(baseUrl, link) : null,
                desc,
                DateParser.parseDateOrNull(pubDate),
                author,
                cats);
    }

    // ── Atom ─────────────────────────────────────────────────────────────────

    private static Feed parseAtom(Document doc, String baseUrl) {
        Element feed = doc.selectFirst("feed");
        String title = text(feed.selectFirst("title"));
        Element linkEl = preferredLink(feed.select("> link"));
        String link  = linkEl != null ? linkEl.attr("href") : baseUrl;
        String desc  = text(feed.selectFirst("subtitle"));

        List<FeedItem> items = feed.select("entry").stream()
                .map(entry -> parseAtomEntry(entry, baseUrl))
                .filter(i -> i.title() != null || i.link() != null)
                .toList();

        return new Feed(
                title != null ? title : "(feed)",
                link  != null && !link.isBlank() ? link : baseUrl,
                desc  != null ? desc : "",
                items);
    }

    private static FeedItem parseAtomEntry(Element entry, String baseUrl) {
        String title = text(entry.selectFirst("title"));
        Element linkEl = preferredLink(entry.select("link"));
        String link  = linkEl != null ? linkEl.attr("href") : null;
        String desc  = inner(entry.selectFirst("content"));
        if (desc == null) desc = inner(entry.selectFirst("summary"));
        String published = text(entry.selectFirst("published"));
        if (published == null) published = text(entry.selectFirst("updated"));
        String author = text(entry.selectFirst("author > name"));
        List<String> cats = entry.select("category").stream()
                .map(c -> c.hasAttr("term") ? c.attr("term") : c.text())
                .filter(t -> !t.isBlank()).toList();

        return new FeedItem(
                title,
                link != null && !link.isBlank() ? Urls.resolve(baseUrl, link) : null,
                desc,
                DateParser.parseInstantOrNull(published),
                author,
                cats);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Element preferredLink(Iterable<Element> links) {
        Element fallback = null;
        for (Element el : links) {
            if (!el.hasAttr("href")) continue;
            String rel = el.attr("rel");
            if ("alternate".equals(rel) || rel.isBlank()) return el;
            if (!"self".equals(rel) && fallback == null) fallback = el;
        }
        return fallback;
    }

    private static String text(Element el) {
        return el == null ? null : el.text().strip();
    }

    private static String inner(Element el) {
        if (el == null) return null;
        String content = el.html().strip();
        return content.isBlank() ? null : content;
    }
}
