package app.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.core.Feed;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteHandler;
import app.routes.github.OllamaReleasesRoute;
import app.routes.github.TransformersReleasesRoute;
import app.routes.github.VllmReleasesRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("releaseSources")
    void fetchesOnlyTheFixedRepositoryAndPreservesPublishedReleaseFields(ReleaseSource source)
            throws Exception {
        List<String> requests = new ArrayList<>();
        String repositoryUrl = "https://github.com/" + source.repository();
        FetchClient client = url -> {
            requests.add(url);
            return releases(repositoryUrl);
        };
        RouteContext context = new RouteContext(source.path(),
                Map.of("owner", "unrelated", "repo", "other"), Map.of("limit", "1"),
                Duration.ofMinutes(20), "https://unrelated.example/feed.xml");

        Feed feed = source.factory().apply(client).handle(context);

        assertEquals(List.of("https://api.github.com/repos/" + source.repository() + "/releases"), requests);
        assertEquals(source.repository() + " releases", feed.title());
        assertEquals(repositoryUrl + "/releases", feed.link());
        assertEquals(1, feed.items().size());
        var item = feed.items().getFirst();
        assertEquals("Release 1.0", item.title());
        assertEquals(repositoryUrl + "/releases/tag/v1.0", item.link());
        assertEquals("<pre>Published release notes.</pre>", item.description());
        assertEquals(Instant.parse("2026-10-20T12:30:00Z"), item.pubDate());
        assertEquals("maintainer", item.author());
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
                        client -> new GithubChangelogRoute(client, null)));
    }

    private static Stream<ReleaseSource> releaseSources() {
        return Stream.of(
                new ReleaseSource("/ollama/releases", "ollama/ollama",
                        client -> new OllamaReleasesRoute(client, MAPPER)),
                new ReleaseSource("/huggingface/transformers/releases", "huggingface/transformers",
                        client -> new TransformersReleasesRoute(client, MAPPER)),
                new ReleaseSource("/vllm/releases", "vllm-project/vllm",
                        client -> new VllmReleasesRoute(client, MAPPER)));
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

    private static String releases(String repositoryUrl) {
        return """
                [
                  {"tag_name":"v1.0","name":"Release 1.0","body":"Published release notes.",
                   "html_url":"%s/releases/tag/v1.0","published_at":"2026-10-20T12:30:00Z",
                   "author":{"login":"maintainer"},"draft":false,"prerelease":false},
                  {"tag_name":"v2.0","name":"Draft release","body":"Not published",
                   "html_url":"%s/releases/tag/v2.0","draft":true,"prerelease":false}
                ]
                """.formatted(repositoryUrl, repositoryUrl);
    }

    private record RssSource(String path, String url, Function<FetchClient, RouteHandler> factory) {
        @Override
        public String toString() {
            return path;
        }
    }

    private record ReleaseSource(String path, String repository,
                                 Function<FetchClient, RouteHandler> factory) {
        @Override
        public String toString() {
            return path;
        }
    }
}
