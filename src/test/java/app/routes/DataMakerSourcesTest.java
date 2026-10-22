package app.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.core.Feed;
import app.core.FetchClient;
import app.core.RouteContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DataMakerSourcesTest {
    private static final String RSS = """
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title>Published research and development</title>
                <link>https://source.example.org/</link>
                <description>Official updates</description>
                <item>
                  <title>A documented project</title>
                  <link>https://source.example.org/articles/project</link>
                  <description><![CDATA[<p>Detailed findings.</p>]]></description>
                  <pubDate>Wed, 21 Oct 2026 08:30:00 GMT</pubDate>
                  <dc:creator>Project team</dc:creator>
                  <category>Research</category>
                </item>
                <item>
                  <title>An undated update</title>
                  <link>https://source.example.org/articles/undated</link>
                </item>
              </channel>
            </rss>
            """;
    private static final String ATOM = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Published research and development</title>
              <link rel="self" href="https://source.example.org/atom.xml"/>
              <link rel="alternate" href="https://source.example.org/"/>
              <subtitle>Official updates</subtitle>
              <entry>
                <title>A documented project</title>
                <link rel="alternate" href="https://source.example.org/articles/project"/>
                <content type="html"><![CDATA[<p>Detailed findings.</p>]]></content>
                <published>2026-10-21T08:30:00Z</published>
                <author><name>Project team</name></author>
                <category term="Research"/>
              </entry>
              <entry>
                <title>An undated update</title>
                <link rel="alternate" href="https://source.example.org/articles/undated"/>
              </entry>
            </feed>
            """;

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void usesOfficialFeedOnMountedPathsAndPreservesPublishedContent(Source source) throws Exception {
        List<String> requests = new ArrayList<>();
        FetchClient client = url -> {
            requests.add(url);
            assertEquals(source.url(), url);
            return source.atom() ? ATOM : RSS;
        };
        RouteContext context = new RouteContext("/my/saved-source", Map.of(), Map.of(), null,
                "https://unrelated.example.org/feed.xml");

        Feed feed = source.create().apply(client).handle(context);

        assertEquals(List.of(source.url()), requests);
        assertEquals("Published research and development", feed.title());
        assertEquals("https://source.example.org/", feed.link());
        assertEquals(2, feed.items().size());
        var first = feed.items().getFirst();
        assertEquals("A documented project", first.title());
        assertEquals("https://source.example.org/articles/project", first.link());
        assertEquals("<p>Detailed findings.</p>", first.description());
        assertEquals(Instant.parse("2026-10-21T08:30:00Z"), first.pubDate());
        assertEquals("Project team", first.author());
        assertEquals(List.of("Research"), first.categories());
        assertEquals("https://source.example.org/articles/undated", feed.items().get(1).link());
        assertNull(feed.items().get(1).pubDate());
    }

    private static Stream<Source> sources() {
        return Stream.of(
                new Source("/ourworldindata/articles", "https://ourworldindata.org/atom.xml", true,
                        client -> new OurWorldInDataRoute(client, null)),
                new Source("/pewresearch/publications", "https://www.pewresearch.org/publications/feed/", false,
                        client -> new PewResearchRoute(client, null)),
                new Source("/hackaday/posts", "https://hackaday.com/blog/feed/", false,
                        client -> new HackadayRoute(client, null)),
                new Source("/raspberrypi/news", "https://www.raspberrypi.com/news/feed/", false,
                        client -> new RaspberryPiRoute(client, null)),
                new Source("/godot/news", "https://godotengine.org/atom.xml", true,
                        client -> new GodotNewsRoute(client, null)),
                new Source("/factorio/blog", "https://factorio.com/blog/rss", true,
                        client -> new FactorioBlogRoute(client, null)));
    }

    private record Source(String path, String url, boolean atom, Function<FetchClient, GenericRssRoute> create) {
        @Override
        public String toString() {
            return path;
        }
    }
}
