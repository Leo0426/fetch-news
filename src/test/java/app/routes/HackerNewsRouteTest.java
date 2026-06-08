package app.routes;

import app.core.Feed;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HackerNewsRouteTest {

    // Minimal HN page HTML: two .athing rows with their subtext rows
    private static final String HN_PAGE = """
            <html><body><table>
              <tr class="athing" id="42">
                <td class="title">
                  <span class="titleline">
                    <a href="https://example.com/thing">Show HN: A new thing</a>
                    <span class="sitestr">example.com</span>
                  </span>
                </td>
              </tr>
              <tr>
                <td class="subtext">
                  <span class="score">100 points</span>
                  <a class="hnuser" href="user?id=alice">alice</a>
                  <span class="age" title="2025-05-31T10:00:00">3 hours ago</span>
                  <a href="item?id=42">25 comments</a>
                </td>
              </tr>
              <tr class="athing" id="99">
                <td class="title">
                  <span class="titleline">
                    <a href="item?id=99">Ask HN: What do you think?</a>
                  </span>
                </td>
              </tr>
              <tr>
                <td class="subtext">
                  <span class="score">50 points</span>
                  <a class="hnuser" href="user?id=bob">bob</a>
                  <span class="age" title="2025-05-31T08:00:00">5 hours ago</span>
                  <a href="item?id=99">discuss</a>
                </td>
              </tr>
            </table></body></html>
            """;

    private static final String EMPTY_PAGE = "<html><body><table></table></body></html>";

    private RouteContext ctx(String feed) {
        return new RouteContext("/hn/" + feed, Map.of("feed", feed), Map.of());
    }

    @Test
    void parsesStoriesFromHtmlPage() throws Exception {
        HackerNewsRoute route = new HackerNewsRoute(url -> HN_PAGE);
        Feed feed = route.handle(ctx("top"));

        assertTrue(feed.title().contains("Top Stories"));
        assertEquals(2, feed.items().size());
    }

    @Test
    void mapsStoryFieldsCorrectly() throws Exception {
        HackerNewsRoute route = new HackerNewsRoute(url -> HN_PAGE);
        var first = route.handle(ctx("top")).items().getFirst();

        assertEquals("Show HN: A new thing", first.title());
        assertEquals("https://example.com/thing", first.link());
        assertEquals("alice", first.author());
        assertNotNull(first.pubDate());
        assertTrue(first.description().contains("100 points"));
        assertTrue(first.description().contains("25 comments"));
        assertEquals(1, first.categories().size());
        assertEquals("example.com", first.categories().getFirst());
    }

    @Test
    void usesHnItemLinkForSelfPost() throws Exception {
        HackerNewsRoute route = new HackerNewsRoute(url -> HN_PAGE);
        var second = route.handle(ctx("top")).items().get(1);

        assertTrue(second.link().startsWith("https://news.ycombinator.com/item?id="));
        assertTrue(second.categories().isEmpty(), "self-post has no sitestr → empty categories");
    }

    @Test
    void throwsEmptyFeedWhenNoStories() {
        HackerNewsRoute route = new HackerNewsRoute(url -> EMPTY_PAGE);
        RouteException ex = assertThrows(RouteException.class, () -> route.handle(ctx("top")));
        assertEquals(RouteError.EMPTY_FEED, ex.error());
    }

    @Test
    void throwsInvalidParamForUnknownFeed() {
        HackerNewsRoute route = new HackerNewsRoute(url -> EMPTY_PAGE);
        RouteContext ctx = new RouteContext("/hn/nope", Map.of("feed", "nope"), Map.of());
        RouteException ex = assertThrows(RouteException.class, () -> route.handle(ctx));
        assertEquals(RouteError.INVALID_PARAMETER, ex.error());
    }

    @Test
    void usesCorrectUrlPerFeedType() throws Exception {
        String[] capturedUrl = {null};
        HackerNewsRoute route = new HackerNewsRoute(url -> { capturedUrl[0] = url; return HN_PAGE; });

        route.handle(ctx("new"));
        assertTrue(capturedUrl[0].contains("/newest"), "new feed must request /newest");

        route.handle(ctx("ask"));
        assertTrue(capturedUrl[0].contains("/ask"));

        route.handle(ctx("job"));
        assertTrue(capturedUrl[0].contains("/jobs"));
    }
}
