package app.routes;

import app.core.Feed;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedditRouteTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LISTING_JSON = """
            {
              "data": {
                "children": [
                  {
                    "kind": "t3",
                    "data": {
                      "title": "Amazing new thing",
                      "url": "https://example.com/thing",
                      "permalink": "/r/programming/comments/abc/amazing_new_thing/",
                      "author": "alice",
                      "score": 1234,
                      "num_comments": 56,
                      "created_utc": 1748700000,
                      "is_self": false,
                      "selftext": ""
                    }
                  },
                  {
                    "kind": "t3",
                    "data": {
                      "title": "Ask r/programming: your opinion?",
                      "url": "https://www.reddit.com/r/programming/comments/xyz/ask/",
                      "permalink": "/r/programming/comments/xyz/ask/",
                      "author": "bob",
                      "score": 500,
                      "num_comments": 99,
                      "created_utc": 1748600000,
                      "is_self": true,
                      "selftext": "What do you all think about this?"
                    }
                  }
                ]
              }
            }
            """;

    private RouteContext ctx(String subreddit) {
        return new RouteContext("/reddit/r/" + subreddit, Map.of("subreddit", subreddit), Map.of());
    }

    @Test
    void parsesFeed() throws Exception {
        RedditRoute route = new RedditRoute(url -> LISTING_JSON, MAPPER);

        Feed feed = route.handle(ctx("programming"));

        assertEquals("r/programming", feed.title());
        assertEquals(2, feed.items().size());
    }

    @Test
    void mapsLinkPostFields() throws Exception {
        RedditRoute route = new RedditRoute(url -> LISTING_JSON, MAPPER);

        var first = route.handle(ctx("programming")).items().getFirst();

        assertEquals("Amazing new thing", first.title());
        assertEquals("https://example.com/thing", first.link());
        assertEquals("alice", first.author());
        assertNotNull(first.pubDate());
        assertTrue(first.description().contains("1234 points"));
    }

    @Test
    void usePermalinkForSelfPost() throws Exception {
        RedditRoute route = new RedditRoute(url -> LISTING_JSON, MAPPER);

        var second = route.handle(ctx("programming")).items().get(1);

        assertTrue(second.link().startsWith("https://www.reddit.com/r/programming/comments/"));
        assertTrue(second.description().contains("What do you all think"));
    }

    @Test
    void throwsEmptyFeed() {
        RedditRoute route = new RedditRoute(url -> "{\"data\":{\"children\":[]}}", MAPPER);

        RouteException ex = assertThrows(RouteException.class, () -> route.handle(ctx("empty")));
        assertEquals(RouteError.EMPTY_FEED, ex.error());
    }
}
