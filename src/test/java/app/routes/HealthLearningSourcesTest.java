package app.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FetchClient;
import app.core.RouteContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class HealthLearningSourcesTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void constructionIsIdleAndRefreshReadsTheOfficialFeedWithoutArticleRequests(Source source) throws Exception {
        List<String> requests = new ArrayList<>();
        FetchClient client = url -> {
            requests.add(url);
            return """
                    <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <channel>
                        <title>Publisher research</title><link>https://publisher.example/</link>
                        <item>
                          <title>Learning from research</title>
                          <link>https://publisher.example/articles/research</link>
                          <description>A published research explanation.</description>
                          <pubDate>Tue, 20 Oct 2026 12:30:00 GMT</pubDate>
                          <dc:creator>Editorial team</dc:creator><category>Research</category>
                        </item>
                      </channel>
                    </rss>
                    """;
        };
        GenericRssRoute route = source.factory().apply(client);
        assertTrue(requests.isEmpty());
        var context = new RouteContext(source.path(), Map.of(), Map.of(), Duration.ofMinutes(30),
                "https://unrelated.example/feed");

        var feed = route.handle(context);

        assertEquals(List.of(source.feedUrl()), requests);
        assertEquals("https://publisher.example/", feed.link());
        assertEquals(1, feed.items().size());
        var item = feed.items().getFirst();
        assertEquals("Learning from research", item.title());
        assertEquals("https://publisher.example/articles/research", item.link());
        assertEquals("A published research explanation.", item.description());
        assertEquals(Instant.parse("2026-10-20T12:30:00Z"), item.pubDate());
        assertEquals("Editorial team", item.author());
        assertEquals(List.of("Research"), item.categories());
    }

    private static Stream<Source> sources() {
        return Stream.of(
                new Source("/kff/health-news", "https://kffhealthnews.org/feed/",
                        client -> new KffHealthNewsRoute(client, null)),
                new Source("/stat/news", "https://www.statnews.com/feed/",
                        client -> new StatNewsRoute(client, null)),
                new Source("/psyche/magazine", "https://psyche.co/feed.rss",
                        client -> new PsycheMagazineRoute(client, null)),
                new Source("/aps/research", "https://www.psychologicalscience.org/news/releases/feed",
                        client -> new ApsResearchNewsRoute(client, null)),
                new Source("/edsurge/news", "https://www.edsurge.com/articles_rss",
                        client -> new EdSurgeNewsRoute(client, null)),
                new Source("/hechinger/report", "https://hechingerreport.org/feed/",
                        client -> new HechingerReportRoute(client, null)));
    }

    private record Source(String path, String feedUrl, Function<FetchClient, GenericRssRoute> factory) {
        @Override
        public String toString() { return path; }
    }
}
