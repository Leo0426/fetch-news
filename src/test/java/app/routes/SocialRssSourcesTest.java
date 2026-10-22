package app.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.core.Feed;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SocialRssSourcesTest {
    private static final String RSS = """
            <rss version="2.0">
              <channel>
                <title>Public interest news</title>
                <link>https://news.example.org/</link>
                <description>News from the source</description>
                <item>
                  <title>Community health update</title>
                  <link>https://news.example.org/articles/community-health</link>
                  <description>New public health services are available.</description>
                  <pubDate>Wed, 21 Oct 2026 08:30:00 GMT</pubDate>
                  <author>News desk</author>
                  <category>Public health</category>
                </item>
                <item>
                  <title>Community library</title>
                  <link>https://news.example.org/articles/community-library</link>
                  <description>The library has extended opening hours.</description>
                </item>
              </channel>
            </rss>
            """;

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void readsOnlyTheOfficialFeedAndPreservesPublishedFields(Source source) throws Exception {
        List<String> requests = new ArrayList<>();
        FetchClient client = url -> {
            requests.add(url);
            assertEquals(source.url(), url);
            return RSS;
        };
        RouteContext context = new RouteContext(source.path(), Map.of(), Map.of(), null,
                "https://unrelated.example.org/feed.xml");

        Feed feed = source.create().apply(client).handle(context);

        assertEquals(List.of(source.url()), requests);
        assertEquals("Public interest news", feed.title());
        assertEquals("https://news.example.org/", feed.link());
        assertEquals(2, feed.items().size());
        var first = feed.items().getFirst();
        assertEquals("Community health update", first.title());
        assertEquals("https://news.example.org/articles/community-health", first.link());
        assertEquals("New public health services are available.", first.description());
        assertEquals(Instant.parse("2026-10-21T08:30:00Z"), first.pubDate());
        assertEquals("News desk", first.author());
        assertEquals(List.of("Public health"), first.categories());
        assertEquals("https://news.example.org/articles/community-library", feed.items().get(1).link());
        assertNull(feed.items().get(1).pubDate());
    }

    private static Stream<Source> sources() {
        return Stream.of(
                new Source("/chinanews/society", "https://www.chinanews.com.cn/rss/society.xml",
                        client -> new ChinanewsSocietyRoute(client, null)),
                new Source("/un/news/zh", "https://news.un.org/feed/subscribe/zh/news/all/rss.xml",
                        client -> new UnNewsChineseRoute(client, null)),
                new Source("/rthk/local", "https://rthk9.rthk.hk/rthk/news/rss/c_expressnews_clocal.xml",
                        client -> new RthkLocalRoute(client, null)),
                new Source("/guardian/world", "https://www.theguardian.com/world/rss",
                        client -> new GuardianWorldRoute(client, null)));
    }

    private record Source(String path, String url, Function<FetchClient, RouteHandler> create) {
        @Override
        public String toString() {
            return path;
        }
    }
}
