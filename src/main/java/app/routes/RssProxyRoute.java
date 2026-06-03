package app.routes;

import app.core.Feed;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import app.support.FeedParser;

/**
 * Base for routes that simply proxy an upstream RSS/Atom feed.
 * Subclasses supply the feed URL (possibly derived from path params).
 */
public abstract class RssProxyRoute implements RouteHandler {

    protected final FetchClient fetchClient;

    protected RssProxyRoute(FetchClient fetchClient) {
        this.fetchClient = fetchClient;
    }

    /**
     * Returns the upstream feed URL for this request.
     * Throws {@link RouteException} for invalid parameters.
     */
    protected abstract String feedUrl(RouteContext context);

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String url = feedUrl(context);
        String xml;
        try {
            xml = fetchClient.get(url);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, e.getMessage(), e);
        }
        try {
            Feed feed = FeedParser.parse(xml, url);
            if (feed.items().isEmpty()) {
                throw new RouteException(RouteError.EMPTY_FEED);
            }
            return feed;
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }
}
