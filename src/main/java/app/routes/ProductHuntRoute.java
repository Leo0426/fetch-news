package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;

/**
 * Route that proxies the Product Hunt daily digest RSS feed.
 *
 * <p>Path: /producthunt/daily
 */
public class ProductHuntRoute extends RssProxyRoute {

    private static final String FEED_URL = "https://www.producthunt.com/feed?category=undefined";

    public ProductHuntRoute(FetchClient fetchClient) {
        super(fetchClient);
    }

    @Override
    protected String feedUrl(RouteContext context) {
        return FEED_URL;
    }
}
