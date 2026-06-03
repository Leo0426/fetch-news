package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;

/**
 * Route that proxies the 少数派 (sspai) articles RSS feed.
 *
 * <p>Path: /sspai/articles
 */
public class SspaiRoute extends RssProxyRoute {

    private static final String FEED_URL = "https://sspai.com/feed";

    public SspaiRoute(FetchClient fetchClient) {
        super(fetchClient);
    }

    @Override
    protected String feedUrl(RouteContext context) {
        return FEED_URL;
    }
}
