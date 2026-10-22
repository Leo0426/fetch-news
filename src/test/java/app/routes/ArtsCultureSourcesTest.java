package app.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ArtsCultureSourcesTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void fetchesOnlyTheOfficialFeedAndPreservesArticleFields(Source source) throws Exception {
        List<String> requests = new ArrayList<>();
        FetchClient client = url -> {
            requests.add(url);
            return """
                    <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <channel><title>Arts and history</title><link>https://publisher.example/</link>
                        <item><title>A restored manuscript</title>
                          <link>https://publisher.example/manuscript</link>
                          <description><![CDATA[<p>New research on a <em>medieval manuscript</em>.</p>]]></description>
                          <pubDate>Wed, 21 Oct 2026 12:00:00 GMT</pubDate>
                          <dc:creator>Editorial team</dc:creator>
                          <category>History</category><category>Art</category>
                        </item>
                        <item><title>An undated exhibition</title>
                          <link>https://publisher.example/exhibition</link>
                        </item>
                      </channel>
                    </rss>
                    """;
        };
        var context = new RouteContext(source.path(), Map.of(), Map.of("limit", "1"),
                Duration.ofMinutes(30), "https://unrelated.example/feed.xml");

        var feed = source.factory().apply(client).handle(context);

        assertEquals(List.of(source.url()), requests);
        assertEquals("Arts and history", feed.title());
        assertEquals("https://publisher.example/", feed.link());
        assertEquals(2, feed.items().size());
        var item = feed.items().getFirst();
        assertEquals("A restored manuscript", item.title());
        assertEquals("https://publisher.example/manuscript", item.link());
        assertEquals("<p>New research on a <em>medieval manuscript</em>.</p>", item.description());
        assertEquals(Instant.parse("2026-10-21T12:00:00Z"), item.pubDate());
        assertEquals("Editorial team", item.author());
        assertEquals(List.of("History", "Art"), item.categories());
        assertEquals("https://publisher.example/exhibition", feed.items().get(1).link());
        assertNull(feed.items().get(1).pubDate());
    }

    private static Stream<Source> sources() {
        return Stream.of(
                new Source("/jstor/daily", "https://daily.jstor.org/feed/",
                        client -> new JstorDailyRoute(client, null)),
                new Source("/public-domain-review/latest", "https://publicdomainreview.org/rss.xml",
                        client -> new PublicDomainReviewRoute(client, null)),
                new Source("/colossal/latest", "https://www.thisiscolossal.com/feed/",
                        client -> new ColossalRoute(client, null)),
                new Source("/aperture/articles", "https://aperture.org/feed/",
                        client -> new ApertureRoute(client, null)),
                new Source("/dezeen/latest", "https://www.dezeen.com/feed/",
                        client -> new DezeenRoute(client, null)),
                new Source("/archdaily/latest", "https://feeds.feedburner.com/Archdaily",
                        client -> new ArchDailyRoute(client, null)));
    }

    private record Source(String path, String url, Function<FetchClient, RouteHandler> factory) {
        @Override
        public String toString() {
            return path;
        }
    }
}
