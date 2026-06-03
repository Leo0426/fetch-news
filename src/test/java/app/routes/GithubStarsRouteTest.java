package app.routes;

import app.core.Feed;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GithubStarsRouteTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String REPOS_JSON = """
            [
              {
                "full_name": "owner/awesome-lib",
                "html_url": "https://github.com/owner/awesome-lib",
                "description": "A very awesome library",
                "pushed_at": "2026-05-01T12:00:00Z",
                "stargazers_count": 4200,
                "language": "Rust"
              },
              {
                "full_name": "other/tool",
                "html_url": "https://github.com/other/tool",
                "description": null,
                "pushed_at": "2026-04-01T12:00:00Z",
                "stargazers_count": 100,
                "language": null
              }
            ]
            """;

    private RouteContext ctx(String user) {
        return new RouteContext("/github/stars/" + user, Map.of("user", user), Map.of());
    }

    @Test
    void parsesFeed() throws Exception {
        GithubStarsRoute route = new GithubStarsRoute(url -> REPOS_JSON, MAPPER);

        Feed feed = route.handle(ctx("alice"));

        assertTrue(feed.title().contains("alice"));
        assertEquals(2, feed.items().size());
    }

    @Test
    void mapsFieldsCorrectly() throws Exception {
        GithubStarsRoute route = new GithubStarsRoute(url -> REPOS_JSON, MAPPER);

        var first = route.handle(ctx("alice")).items().getFirst();

        assertTrue(first.title().contains("owner/awesome-lib"));
        assertTrue(first.title().contains("Rust"));
        assertEquals("https://github.com/owner/awesome-lib", first.link());
        assertTrue(first.description().contains("4200"));
        assertTrue(first.description().contains("awesome library"));
        assertTrue(first.categories().contains("Rust"));
    }

    @Test
    void throwsEmptyFeed() {
        GithubStarsRoute route = new GithubStarsRoute(url -> "[]", MAPPER);

        RouteException ex = assertThrows(RouteException.class, () -> route.handle(ctx("nobody")));
        assertEquals(RouteError.EMPTY_FEED, ex.error());
    }
}
