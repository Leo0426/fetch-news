package app.routes;

import app.CredentialConfig;
import app.CredentialConfigStore;
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

class BilibiliVideoRouteTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Fake NAV API response: img/sub URL stems produce a 32-char string with identity permutation.
    // imgStem = "abcdefghijklmnop" (16), subStem = "qrstuvwxyz012345" (16) → r = 32 chars.
    private static final String NAV_JSON = """
            {"code":0,"data":{"wbi_img":{
              "img_url":"https://i0.hdslb.com/bfs/wbi/abcdefghijklmnop.png",
              "sub_url":"https://i0.hdslb.com/bfs/wbi/qrstuvwxyz012345.png"
            }}}""";

    // Identity permutation [0..63]; chars 32-63 beyond r's length are skipped → key = r[0..31].
    private static final String FAKE_JS =
            "var x=[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23," +
            "24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47," +
            "48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63];";

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
                      "pic": "https://img.example.com/cover.jpg",
                      "created": 1748700000
                    },
                    {
                      "bvid": "BV2yy411d8YF",
                      "title": "Second Video",
                      "author": "testup",
                      "description": "",
                      "created": 1748600000
                    }
                  ]
                }
              }
            }
            """;

    /** Mock FetchClient dispatches by URL pattern. */
    private static app.core.FetchClient mockFetcher(String videoJson) {
        return url -> {
            if (url.contains("web-interface/nav"))    return NAV_JSON;
            if (url.contains("bili-header.umd.js"))  return FAKE_JS;
            return videoJson;
        };
    }

    private static BilibiliVideoRoute route(String videoJson) {
        CacheService cache = new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30));
        CredentialConfigStore creds = new CredentialConfigStore(
                java.nio.file.Path.of("build/test-creds.json"), MAPPER);
        return new BilibiliVideoRoute(mockFetcher(videoJson), MAPPER, cache, creds);
    }

    private RouteContext ctx(String uid) {
        return new RouteContext("/bilibili/user/video/" + uid, Map.of("uid", uid), Map.of());
    }

    @Test
    void parsesFeed() throws Exception {
        Feed feed = route(RESPONSE_JSON).handle(ctx("12345"));

        assertTrue(feed.title().contains("testup"));
        assertEquals(2, feed.items().size());
    }

    @Test
    void mapsFieldsCorrectly() throws Exception {
        var first = route(RESPONSE_JSON).handle(ctx("12345")).items().getFirst();

        assertEquals("My First Video", first.title());
        assertTrue(first.link().contains("BV1xx411c7XE"));
        assertEquals("testup", first.author());
        assertNotNull(first.pubDate());
        assertTrue(first.description().contains("demo video"));
        assertTrue(first.description().contains("<img"));
    }

    @Test
    void throwsOnApiError() {
        String errorJson = "{\"code\": -404, \"message\": \"user not found\"}";

        RouteException ex = assertThrows(RouteException.class,
                () -> route(errorJson).handle(ctx("99999")));
        assertEquals(RouteError.UPSTREAM_REQUEST_FAILED, ex.error());
    }

    @Test
    void throwsEmptyFeed() {
        String emptyJson = "{\"code\":0,\"data\":{\"list\":{\"vlist\":[]}}}";

        RouteException ex = assertThrows(RouteException.class,
                () -> route(emptyJson).handle(ctx("12345")));
        assertEquals(RouteError.EMPTY_FEED, ex.error());
    }
}
