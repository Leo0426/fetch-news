package app.core;

import app.support.XmlEscaper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Renders normalized feeds as hand-written RSS 2.0 XML.
 */
public class RssRenderer implements FeedRenderer {
    private static final DateTimeFormatter RFC_1123 =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    public RssRenderer() {}

    @Override
    public String format() { return "rss"; }

    @Override
    public String contentType() { return "application/rss+xml; charset=UTF-8"; }

    /**
     * Renders a feed into an RSS 2.0 document.
     *
     * @param feed feed to render
     * @return RSS XML document
     */
    @Override
    public String render(Feed feed) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<rss version=\"2.0\">\n<channel>\n");
        element(xml, "title", feed.title());
        element(xml, "link", feed.link());
        element(xml, "description", feed.description());
        element(xml, "lastBuildDate", RFC_1123.format(Instant.now()));
        for (FeedItem item : feed.items()) {
            xml.append("<item>\n");
            element(xml, "title", item.title());
            element(xml, "link", item.link());
            xml.append("<guid isPermaLink=\"true\">").append(XmlEscaper.escape(item.link())).append("</guid>\n");
            if (item.description() != null && !item.description().isBlank()) {
                xml.append("<description><![CDATA[")
                        .append(item.description().replace("]]>", "]]]]><![CDATA[>"))
                        .append("]]></description>\n");
            }
            if (item.pubDate() != null) {
                element(xml, "pubDate", RFC_1123.format(item.pubDate()));
            }
            if (item.author() != null && !item.author().isBlank()) {
                element(xml, "author", item.author());
            }
            for (String category : item.categories()) {
                element(xml, "category", category);
            }
            xml.append("</item>\n");
        }
        xml.append("</channel>\n</rss>\n");
        return xml.toString();
    }

    private void element(StringBuilder xml, String name, String value) {
        xml.append('<').append(name).append('>')
                .append(XmlEscaper.escape(value))
                .append("</").append(name).append(">\n");
    }
}
