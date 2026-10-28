package app.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.core.Feed;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteHandler;
import app.routes.github.OllamaReleasesRoute;
import app.routes.github.TransformersReleasesRoute;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CuratedAiGithubRoutesTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("rssSources")
    void fetchesTheOfficialFeedOnceAndPreservesPublishedFields(RssSource source) throws Exception {
        List<String> requests = new ArrayList<>();
        FetchClient client = url -> {
            requests.add(url);
            return rss();
        };
        RouteContext context = new RouteContext(source.path(), Map.of(), Map.of("limit", "1"),
                Duration.ofMinutes(20), "https://unrelated.example/feed.xml");

        Feed feed = source.factory().apply(client).handle(context);

        assertEquals(List.of(source.url()), requests);
        assertEquals("Publisher News", feed.title());
        assertEquals("https://publisher.example/news", feed.link());
        assertEquals(2, feed.items().size());
        var item = feed.items().getFirst();
        assertEquals("A research update", item.title());
        assertEquals("https://publisher.example/news/research", item.link());
        assertEquals("Research findings and implementation details.", item.description());
        assertEquals(Instant.parse("2026-10-20T12:30:00Z"), item.pubDate());
        assertEquals("Research team", item.author());
        assertEquals(List.of("AI"), item.categories());
        assertNull(feed.items().get(1).pubDate());
    }

    @Test
    void usesHuggingFacePermalinkGuidsWhenAnItemHasNoLinkElement() throws Exception {
        FetchClient client = url -> """
                <rss version="2.0"><channel>
                  <title>Hugging Face Blog</title><link>https://huggingface.co/blog</link>
                  <item><title>An open model</title>
                    <guid isPermaLink="true">https://huggingface.co/blog/open-model</guid>
                    <pubDate>Tue, 20 Oct 2026 12:30:00 GMT</pubDate>
                  </item>
                </channel></rss>
                """;

        Feed feed = new HuggingFaceBlogRoute(client, null)
                .handle(new RouteContext("/huggingface/blog", Map.of(), Map.of()));

        assertEquals("https://huggingface.co/blog/open-model", feed.items().getFirst().link());
        assertEquals(Instant.parse("2026-10-20T12:30:00Z"), feed.items().getFirst().pubDate());
    }

    private static Stream<RssSource> rssSources() {
        return Stream.of(
                new RssSource("/openai/news", "https://openai.com/news/rss.xml",
                        client -> new OpenAiNewsRoute(client, null)),
                new RssSource("/huggingface/blog", "https://huggingface.co/blog/feed.xml",
                        client -> new HuggingFaceBlogRoute(client, null)),
                new RssSource("/google/research", "https://research.google/blog/rss/",
                        client -> new GoogleResearchRoute(client, null)),
                new RssSource("/github/open-source", "https://github.blog/open-source/feed/",
                        client -> new GithubOpenSourceRoute(client, null)),
                new RssSource("/github/changelog", "https://github.blog/changelog/feed/",
                        client -> new GithubChangelogRoute(client, null)),
                new RssSource("/ollama/releases", "https://github.com/ollama/ollama/releases.atom",
                        client -> new OllamaReleasesRoute(client, null)),
                new RssSource("/huggingface/transformers/releases",
                        "https://github.com/huggingface/transformers/releases.atom",
                        client -> new TransformersReleasesRoute(client, null)));
    }

    private static String rss() {
        return """
                <rss version="2.0"><channel>
                  <title>Publisher News</title><link>https://publisher.example/news</link>
                  <description>Research and engineering</description>
                  <item>
                    <title>A research update</title>
                    <link>https://publisher.example/news/research</link>
                    <description>Research findings and implementation details.</description>
                    <pubDate>Tue, 20 Oct 2026 12:30:00 GMT</pubDate>
                    <author>Research team</author><category>AI</category>
                  </item>
                  <item><title>An undated update</title>
                    <link>https://publisher.example/news/undated</link>
                  </item>
                </channel></rss>
                """;
    }

    private record RssSource(String path, String url, Function<FetchClient, RouteHandler> factory) {
        @Override
        public String toString() {
            return path;
        }
    }
}
