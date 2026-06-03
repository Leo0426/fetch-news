package app.routes;

import app.core.Feed;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GithubReleaseRouteTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RELEASES_JSON = """
            [
              {
                "tag_name": "v1.0.0",
                "name": "First Release",
                "body": "Initial release",
                "html_url": "https://github.com/owner/repo/releases/tag/v1.0.0",
                "published_at": "2026-01-01T10:00:00Z",
                "draft": false,
                "prerelease": false,
                "author": {"login": "alice"}
              },
              {
                "tag_name": "v0.9.0-beta",
                "name": "Beta",
                "body": "",
                "html_url": "https://github.com/owner/repo/releases/tag/v0.9.0-beta",
                "published_at": "2025-12-01T10:00:00Z",
                "draft": false,
                "prerelease": true,
                "author": {"login": "bob"}
              },
              {
                "tag_name": "v0.8.0",
                "name": "Draft",
                "body": "Should be excluded",
                "html_url": "https://github.com/owner/repo/releases/tag/v0.8.0",
                "published_at": "2025-11-01T10:00:00Z",
                "draft": true,
                "prerelease": false,
                "author": {"login": "carol"}
              }
            ]
            """;

    @Test
    void parsesFeedFromReleasesJson() throws Exception {
        GithubReleaseRoute route = new GithubReleaseRoute(url -> RELEASES_JSON, MAPPER);
        RouteContext ctx = new RouteContext("/github/releases/owner/repo",
                Map.of("owner", "owner", "repo", "repo"), Map.of());

        Feed feed = route.handle(ctx);

        assertEquals("owner/repo releases", feed.title());
        assertEquals("https://github.com/owner/repo/releases", feed.link());
        assertEquals(2, feed.items().size(), "draft releases must be excluded");
    }

    @Test
    void mapsFieldsCorrectly() throws Exception {
        GithubReleaseRoute route = new GithubReleaseRoute(url -> RELEASES_JSON, MAPPER);
        RouteContext ctx = new RouteContext("/github/releases/owner/repo",
                Map.of("owner", "owner", "repo", "repo"), Map.of());

        var first = route.handle(ctx).items().getFirst();

        assertEquals("First Release", first.title());
        assertEquals("https://github.com/owner/repo/releases/tag/v1.0.0", first.link());
        assertNotNull(first.pubDate());
        assertEquals("alice", first.author());
        assertNotNull(first.description());
        assertTrue(first.description().contains("Initial release"));
    }

    @Test
    void appendsPrereleaseLabel() throws Exception {
        GithubReleaseRoute route = new GithubReleaseRoute(url -> RELEASES_JSON, MAPPER);
        RouteContext ctx = new RouteContext("/github/releases/owner/repo",
                Map.of("owner", "owner", "repo", "repo"), Map.of());

        var second = route.handle(ctx).items().get(1);

        assertTrue(second.title().contains("pre-release"), "prerelease flag must be reflected in title");
    }

    @Test
    void throwsEmptyFeedWhenArrayIsEmpty() {
        GithubReleaseRoute route = new GithubReleaseRoute(url -> "[]", MAPPER);
        RouteContext ctx = new RouteContext("/github/releases/owner/repo",
                Map.of("owner", "owner", "repo", "repo"), Map.of());

        RouteException ex = assertThrows(RouteException.class, () -> route.handle(ctx));
        assertEquals(RouteError.EMPTY_FEED, ex.error());
    }

    @Test
    void throwsInvalidContentWhenNotArray() {
        GithubReleaseRoute route = new GithubReleaseRoute(url -> "{\"message\":\"Not Found\"}", MAPPER);
        RouteContext ctx = new RouteContext("/github/releases/owner/repo",
                Map.of("owner", "owner", "repo", "repo"), Map.of());

        RouteException ex = assertThrows(RouteException.class, () -> route.handle(ctx));
        assertEquals(RouteError.UPSTREAM_INVALID_CONTENT, ex.error());
    }
}
