package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;

/**
 * Route that proxies the TechCrunch RSS feed.
 *
 * <p>Path: /techcrunch
 */
public class TechCrunchRoute extends RssProxyRoute {

    private static final String FEED_URL = "https://techcrunch.com/feed/";

    public TechCrunchRoute(FetchClient fetchClient) {
        super(fetchClient);
    }

    @Override
    protected String feedUrl(RouteContext context) {
        return FEED_URL;
    }
}
