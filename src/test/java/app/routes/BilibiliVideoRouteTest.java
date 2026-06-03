package app.routes;

import app.core.Feed;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliVideoRouteTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESPONSE_JSON = """
            {
              "code": 0,
              "data": {
                "list": {
                  "vlist": [
                    {
                      "bvid": "BV1xx411c7XE",
                      "title": "My First Video",
                      "author": "testup",
                      "description": "A demo video description.",
                      "created": 1748700000
                    },
                    {
                      "bvid": "BV2yy411d8YF",
                      "title": "Second Video",
                      "author": "testup",
                      "description": null,
                      "created": 1748600000
                    }
                  ]
                }
              }
            }
            """;

    private RouteContext ctx(String uid) {
        return new RouteContext("/bilibili/user/video/" + uid, Map.of("uid", uid), Map.of());
    }

    @Test
    void parsesFeed() throws Exception {
        BilibiliVideoRoute route = new BilibiliVideoRoute(url -> RESPONSE_JSON, MAPPER);

        Feed feed = route.handle(ctx("12345"));

        assertTrue(feed.title().contains("testup"));
        assertEquals(2, feed.items().size());
    }

    @Test
    void mapsFieldsCorrectly() throws Exception {
        BilibiliVideoRoute route = new BilibiliVideoRoute(url -> RESPONSE_JSON, MAPPER);

        var first = route.handle(ctx("12345")).items().getFirst();

        assertEquals("My First Video", first.title());
        assertTrue(first.link().contains("BV1xx411c7XE"));
        assertEquals("testup", first.author());
        assertNotNull(first.pubDate());
        assertTrue(first.description().contains("demo video"));
    }

    @Test
    void throwsOnApiError() {
        String errorJson = "{\"code\": -404, \"message\": \"user not found\"}";
        BilibiliVideoRoute route = new BilibiliVideoRoute(url -> errorJson, MAPPER);

        RouteException ex = assertThrows(RouteException.class, () -> route.handle(ctx("99999")));
        assertEquals(RouteError.UPSTREAM_REQUEST_FAILED, ex.error());
    }

    @Test
    void throwsEmptyFeed() {
        String emptyJson = "{\"code\":0,\"data\":{\"list\":{\"vlist\":[]}}}";
        BilibiliVideoRoute route = new BilibiliVideoRoute(url -> emptyJson, MAPPER);

        RouteException ex = assertThrows(RouteException.class, () -> route.handle(ctx("12345")));
        assertEquals(RouteError.EMPTY_FEED, ex.error());
    }
}
