package app.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FeedParserTest {
    @Test
    void readsEncodedRssBodyAndCreatorWhenDescriptionIsMissing() {
        var feed = FeedParser.parse("""
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <channel><title>Engineering</title><link>https://example.com</link>
                    <item><title>A design study</title><link>https://example.com/study</link>
                      <content:encoded><![CDATA[<p>A <strong>technical</strong> article.</p>]]></content:encoded>
                      <dc:creator><![CDATA[Engineering Team]]></dc:creator>
                    </item>
                  </channel>
                </rss>
                """, "https://example.com/feed");

        var item = feed.items().getFirst();
        assertEquals("<p>A <strong>technical</strong> article.</p>", item.description());
        assertEquals("Engineering Team", item.author());
    }

    @Test
    void keepsPublisherDescriptionAndAuthorWhenEncodedAlternativesExist() {
        var item = FeedParser.parse("""
                <rss xmlns:content="http://purl.org/rss/1.0/modules/content/"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <channel><title>News</title><item><title>A story</title>
                    <description><![CDATA[<p>Publisher summary.</p>]]></description>
                    <content:encoded><![CDATA[<p>Long article.</p>]]></content:encoded>
                    <author>Original Author</author><dc:creator>Fallback Author</dc:creator>
                  </item></channel>
                </rss>
                """, "https://example.com/feed").items().getFirst();

        assertEquals("<p>Publisher summary.</p>", item.description());
        assertEquals("Original Author", item.author());
    }

    @Test
    void usesEncodedBodyWhenDescriptionOnlyContainsWhitespace() {
        var item = FeedParser.parse("""
                <rss xmlns:content="http://purl.org/rss/1.0/modules/content/">
                  <channel><title>News</title><item><title>A story</title>
                    <description><![CDATA[   ]]></description>
                    <content:encoded><![CDATA[<p>Article body.</p>]]></content:encoded>
                  </item></channel>
                </rss>
                """, "https://example.com/feed").items().getFirst();

        assertEquals("<p>Article body.</p>", item.description());
    }

    @Test
    void preservesInlineAtomXhtmlMarkup() {
        var item = FeedParser.parse("""
                <feed xmlns="http://www.w3.org/2005/Atom"><title>Articles</title>
                  <entry><title>A study</title><link href="https://example.com/study"/>
                    <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Body <em>text</em>.</p></div></content>
                  </entry>
                </feed>
                """, "https://example.com/feed").items().getFirst();

        assertEquals("<div xmlns=\"http://www.w3.org/1999/xhtml\"><p>Body <em>text</em>.</p></div>", item.description());
    }
}
