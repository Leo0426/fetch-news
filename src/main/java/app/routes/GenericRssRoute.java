package app.routes;

import app.core.Feed;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import app.support.FeedParser;

/**
 * Fetches and parses any RSS 2.0 or Atom feed URL supplied via {@link RouteContext#feedUrl()}.
 *
 * <p>Register this handler at {@code /rss} in {@code AppRuntime}, then create a mount alias
 * (e.g. {@code /my-blog} → {@code /rss}) with {@code feedUrl} set to the target feed address.
 */
public class GenericRssRoute implements RouteHandler {

    private final FetchClient client;

    public GenericRssRoute(FetchClient client) {
        this.client = client;
    }

    @Override
    public Feed handle(RouteContext ctx) throws Exception {
        String url = ctx.feedUrl();
        if (url == null || url.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER,
                    "feedUrl is required — set it in the route config when using /rss as source");
        }
        String xml = client.get(url);
        Feed feed = FeedParser.parse(xml, url);
        if (feed.items().isEmpty()) {
            throw new RouteException(RouteError.EMPTY_FEED);
        }
        return feed;
    }
}
