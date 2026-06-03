package app.routes;

import app.core.CacheService;
import app.core.Feed;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HackerNewsRouteTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TOP_IDS_JSON = "[42, 99]";

    private static final String STORY_42 = """
            {
              "id": 42,
              "type": "story",
              "title": "Show HN: A new thing",
              "url": "https://example.com/thing",
              "by": "alice",
              "time": 1748700000,
              "score": 100,
              "descendants": 25
            }
            """;

    private static final String STORY_99 = """
            {
              "id": 99,
              "type": "story",
              "title": "Ask HN: What do you think?",
              "by": "bob",
              "time": 1748600000,
              "score": 50,
              "descendants": 10
            }
            """;

    private app.core.FetchClient mockFetcher() {
        return url -> {
            if (url.contains("topstories")) return TOP_IDS_JSON;
            if (url.contains("/42.json")) return STORY_42;
            if (url.contains("/99.json")) return STORY_99;
            throw new IllegalArgumentException("unexpected url: " + url);
        };
    }

    private RouteContext ctx(String feed) {
        return new RouteContext("/hn/" + feed, Map.of("feed", feed), Map.of());
    }

    @Test
    void parsesFeedFromTopStoriesApi() throws Exception {
        CacheService cache = new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30));
        HackerNewsRoute route = new HackerNewsRoute(mockFetcher(), cache, MAPPER);

        Feed feed = route.handle(ctx("top"));

        assertTrue(feed.title().contains("Top Stories"));
        assertEquals(2, feed.items().size());
    }

    @Test
    void mapsStoryFieldsCorrectly() throws Exception {
        CacheService cache = new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30));
        HackerNewsRoute route = new HackerNewsRoute(mockFetcher(), cache, MAPPER);

        var first = route.handle(ctx("top")).items().getFirst();

        assertEquals("Show HN: A new thing", first.title());
        assertEquals("https://example.com/thing", first.link(), "story with url must use url as link");
        assertEquals("alice", first.author());
        assertNotNull(first.pubDate());
        assertTrue(first.description().contains("100 points"));
    }

    @Test
    void usesItemUrlForAskHnWithNoUrl() throws Exception {
        CacheService cache = new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30));
        HackerNewsRoute route = new HackerNewsRoute(mockFetcher(), cache, MAPPER);

        var second = route.handle(ctx("top")).items().get(1);

        assertTrue(second.link().startsWith("https://news.ycombinator.com/item?id="));
    }

    @Test
    void cachesStoryDetailPages() throws Exception {
        CacheService cache = new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30));
        int[] callCount = {0};
        app.core.FetchClient countingFetcher = url -> {
            if (url.contains("topstories")) return TOP_IDS_JSON;
            callCount[0]++;
            if (url.contains("/42.json")) return STORY_42;
            if (url.contains("/99.json")) return STORY_99;
            throw new IllegalArgumentException("unexpected url: " + url);
        };
        HackerNewsRoute route = new HackerNewsRoute(countingFetcher, cache, MAPPER);

        route.handle(ctx("top"));
        route.handle(ctx("top"));

        assertEquals(2, callCount[0], "story detail fetches must be cached after the first call");
    }

    @Test
    void throwsEmptyFeedWhenNoStories() {
        CacheService cache = new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30));
        HackerNewsRoute route = new HackerNewsRoute(url -> "[]", cache, MAPPER);

        RouteException ex = assertThrows(RouteException.class, () -> route.handle(ctx("top")));
        assertEquals(RouteError.EMPTY_FEED, ex.error());
    }

    @Test
    void throwsInvalidParamForUnknownFeed() {
        CacheService cache = new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30));
        HackerNewsRoute route = new HackerNewsRoute(url -> "[]", cache, MAPPER);
        RouteContext ctx = new RouteContext("/hn/nope", Map.of("feed", "nope"), Map.of());

        RouteException ex = assertThrows(RouteException.class, () -> route.handle(ctx));
        assertEquals(RouteError.INVALID_PARAMETER, ex.error());
    }
}
