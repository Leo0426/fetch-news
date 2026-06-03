package app.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for RSS XML rendering.
 */
class RssRendererTest {
    /**
     * Verifies XML escaping, CDATA descriptions, and optional RSS item fields.
     */
    @Test
    void rendersRssWithEscapedXmlAndOptionalFields() {
        Feed feed = new Feed(
                "A & B",
                "https://example.com/feed?x=1&y=2",
                "News <daily>",
                List.of(new FeedItem(
                        "Title <one>",
                        "https://example.com/a?x=1&y=2",
                        "<p>Body & more</p>",
                        Instant.parse("2026-05-22T08:00:00Z"),
                        "A&B",
                        List.of("java", "rss&xml"))));

        String xml = new RssRenderer().render(feed);

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(xml.contains("<title>A &amp; B</title>"));
        assertTrue(xml.contains("<link>https://example.com/feed?x=1&amp;y=2</link>"));
        assertTrue(xml.contains("<guid isPermaLink=\"true\">https://example.com/a?x=1&amp;y=2</guid>"));
        assertTrue(xml.contains("<description><![CDATA[<p>Body & more</p>]]></description>"));
        assertTrue(xml.contains("<author>A&amp;B</author>"));
        assertTrue(xml.contains("<category>rss&amp;xml</category>"));
        assertTrue(xml.contains("<pubDate>Fri, 22 May 2026 08:00:00 GMT</pubDate>"));
        assertFalse(xml.contains("Title <one>"));
    }
}
