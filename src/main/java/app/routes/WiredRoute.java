package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;

/**
 * Route that proxies the WIRED latest stories RSS feed.
 *
 * <p>Path: /wired/latest
 */
public class WiredRoute extends RssProxyRoute {

    private static final String FEED_URL = "https://www.wired.com/feed/rss";

    public WiredRoute(FetchClient fetchClient) {
        super(fetchClient);
    }

    @Override
    protected String feedUrl(RouteContext context) {
        return FEED_URL;
    }
}
