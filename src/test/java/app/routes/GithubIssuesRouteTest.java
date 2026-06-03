package app.routes;

import app.core.Feed;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GithubIssuesRouteTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ISSUES_JSON = """
            [
              {
                "title": "Bug: crash on startup",
                "html_url": "https://github.com/owner/repo/issues/1",
                "body": "The app crashes when launched.",
                "created_at": "2026-01-01T10:00:00Z",
                "user": {"login": "alice"},
                "labels": [{"name": "bug"}, {"name": "critical"}]
              },
              {
                "title": "Add dark mode",
                "html_url": "https://github.com/owner/repo/issues/2",
                "body": null,
                "created_at": "2026-01-02T10:00:00Z",
                "user": {"login": "bob"},
                "labels": [{"name": "enhancement"}],
                "pull_request": {}
              }
            ]
            """;

    private RouteContext ctx() {
        return new RouteContext("/github/issues/owner/repo", Map.of("owner", "owner", "repo", "repo"), Map.of());
    }

    @Test
    void parsesFeed() throws Exception {
        GithubIssuesRoute route = new GithubIssuesRoute(url -> ISSUES_JSON, MAPPER);

        Feed feed = route.handle(ctx());

        assertEquals("owner/repo issues", feed.title());
        assertEquals(1, feed.items().size(), "pull requests must be excluded");
    }

    @Test
    void mapsFieldsCorrectly() throws Exception {
        GithubIssuesRoute route = new GithubIssuesRoute(url -> ISSUES_JSON, MAPPER);

        var item = route.handle(ctx()).items().getFirst();

        assertEquals("Bug: crash on startup", item.title());
        assertEquals("https://github.com/owner/repo/issues/1", item.link());
        assertEquals("alice", item.author());
        assertNotNull(item.pubDate());
        assertTrue(item.categories().contains("bug"));
        assertTrue(item.description().contains("crashes"));
    }

    @Test
    void throwsEmptyFeedWhenNoIssues() {
        GithubIssuesRoute route = new GithubIssuesRoute(url -> "[]", MAPPER);

        RouteException ex = assertThrows(RouteException.class, () -> route.handle(ctx()));
        assertEquals(RouteError.EMPTY_FEED, ex.error());
    }
}
