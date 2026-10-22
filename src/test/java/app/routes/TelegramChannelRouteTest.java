package app.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.Feed;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.routes.telegram.NewlearnerChannelRoute;
import app.routes.telegram.ScitechFansChannelRoute;
import app.routes.telegram.SolidotChannelRoute;
import app.routes.telegram.TelegramChannelRoute;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelegramChannelRouteTest {
    @Test
    void readsOnePublicPreviewAndPreservesContentWithoutWidgetMetadata() throws Exception {
        List<String> requests = new ArrayList<>();
        var route = new TelegramChannelRoute(url -> {
            requests.add(url);
            return page("""
                    <div class="tgme_widget_message" data-post="devnews/41">
                      <div class="tgme_widget_message_text">New compiler<br>Details &amp; examples.
                        <a href="https://t.me/s/devnews?q=%23Rust">#Rust</a></div>
                      <span class="tgme_widget_message_from_author">Editor</span>
                      <a class="tgme_widget_message_date" href="https://t.me/devnews/41">
                        <time datetime="2026-10-21T12:30:00+00:00">12:30</time></a>
                      <span class="tgme_widget_message_views">999K views</span>
                      <div class="tgme_widget_message_reactions">👍 999</div>
                    </div>
                    <div class="tgme_widget_message" data-post="devnews/42">
                      <div class="tgme_widget_message_text">https://example.com/research</div>
                      <a class="tgme_widget_message_link_preview" href="https://example.com/research">
                        <div class="link_preview_title">New research</div>
                        <div class="link_preview_description">Research summary.</div></a>
                    </div>
                    <div class="tgme_widget_message" data-post="devnews/42">
                      <div class="tgme_widget_message_text">Duplicate</div></div>
                    <div class="tgme_widget_message" data-post="other/43">
                      <div class="tgme_widget_message_text">Wrong channel</div></div>
                    <div class="tgme_widget_message" data-post="devnews/44">
                      <div class="message_media_not_supported">Open in Telegram</div></div>
                    """);
        });

        Feed feed = route.handle(new RouteContext("/telegram/channel/devnews", Map.of("channel", "devnews"),
                Map.of("limit", "1", "before", "1")));

        assertEquals(List.of("https://t.me/s/devnews"), requests);
        assertEquals("Developer news", feed.title());
        assertEquals("https://t.me/devnews", feed.link());
        assertEquals(2, feed.items().size());
        var preview = feed.items().getFirst();
        assertEquals("New research", preview.title());
        assertEquals("https://t.me/devnews/42", preview.link());
        assertTrue(preview.description().contains("Research summary."));
        assertNull(preview.pubDate());
        assertEquals("Developer news", preview.author());
        var item = feed.items().getLast();
        assertEquals("New compiler", item.title());
        assertEquals("https://t.me/devnews/41", item.link());
        assertEquals(Instant.parse("2026-10-21T12:30:00Z"), item.pubDate());
        assertEquals("Editor", item.author());
        assertEquals(List.of("Rust"), item.categories());
        assertTrue(item.description().contains("Details &amp; examples."));
        assertFalse(item.description().contains("999"));
    }

    @Test
    void usesTheHeadlineAfterLeadingTagsAndCleansPreviewMarkup() throws Exception {
        String headline = "An in-depth developer update with a complete headline that remains readable "
                + "and is preserved even when it is longer than one hundred characters";
        String html = page("<div class='tgme_widget_message' data-post='devnews/1'>"
                + "<div class='tgme_widget_message_text'><a href='?q=%23News'>#News</a><br><br>"
                + "<b>" + headline + "</b><br><a href='https://example.com/post' onclick='alert(1)'>Read more</a>"
                + "<script>alert(2)</script></div></div>");
        var feed = new TelegramChannelRoute(url -> html)
                .handle(new RouteContext("/telegram/channel/devnews", Map.of("channel", "devnews"), Map.of()));
        var item = feed.items().getFirst();
        assertEquals(headline, item.title());
        assertEquals(List.of("News"), item.categories());
        assertFalse(item.description().contains("onclick"));
        assertFalse(item.description().contains("script"));
        assertFalse(item.description().contains("#News"));
        assertTrue(item.description().contains("https://example.com/post"));
    }

    @Test
    void validatesTheChannelBeforeSendingAnyRequest() {
        var route = new TelegramChannelRoute(url -> { throw new AssertionError("Unexpected fetch"); });
        for (String channel : List.of("", "../admin", "name?before=2", "@devnews", "https://t.me/devnews")) {
            var error = assertThrows(RouteException.class,
                    () -> route.handle(new RouteContext("/telegram/channel", Map.of("channel", channel), Map.of())));
            assertEquals(RouteError.INVALID_PARAMETER, error.error());
        }
    }

    @Test
    void distinguishesUnavailablePreviewEmptyFeedAndNetworkFailure() {
        var context = new RouteContext("/telegram/channel/devnews", Map.of("channel", "devnews"), Map.of());
        var missing = assertThrows(RouteException.class,
                () -> new TelegramChannelRoute(url -> "<html>Contact @devnews</html>").handle(context));
        assertEquals(RouteError.UPSTREAM_INVALID_CONTENT, missing.error());
        assertTrue(missing.getMessage().contains("public"));
        var empty = assertThrows(RouteException.class,
                () -> new TelegramChannelRoute(url -> page("")).handle(context));
        assertEquals(RouteError.EMPTY_FEED, empty.error());
        var network = assertThrows(RouteException.class,
                () -> new TelegramChannelRoute(url -> { throw new IOException("offline"); }).handle(context));
        assertEquals(RouteError.UPSTREAM_REQUEST_FAILED, network.error());
        var original = new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "rate limited");
        assertSame(original, assertThrows(RouteException.class,
                () -> new TelegramChannelRoute(url -> { throw original; }).handle(context)));
    }

    @Test
    void fixedSourcesIgnoreInjectedChannelAndFeedUrl() throws Exception {
        List<String> requests = new ArrayList<>();
        FetchClient client = url -> {
            requests.add(url);
            String channel = url.substring(url.lastIndexOf('/') + 1);
            return page("<div class='tgme_widget_message' data-post='" + channel + "/1'>"
                    + "<div class='tgme_widget_message_text'>An update</div></div>");
        };
        var context = new RouteContext("/alias", Map.of("channel", "unrelated"), Map.of());
        for (var route : List.of(new ScitechFansChannelRoute(client), new NewlearnerChannelRoute(client),
                new SolidotChannelRoute(client))) {
            assertEquals(1, route.handle(context).items().size());
        }
        assertEquals(List.of("https://t.me/s/scitech_fans", "https://t.me/s/NewlearnerChannel",
                "https://t.me/s/solidot"), requests);
    }

    private static String page(String messages) {
        return "<html><div class='tgme_channel_info_header_title'>Developer news</div>"
                + "<div class='tgme_channel_info_description'>Tools and research</div>"
                + "<section class='tgme_channel_history'>" + messages + "</section></html>";
    }
}
