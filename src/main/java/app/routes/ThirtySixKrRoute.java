package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;

/**
 * Route that proxies the 36氪 (36kr) news RSS feed.
 *
 * <p>Path: /36kr/news
 */
public class ThirtySixKrRoute extends RssProxyRoute {

    private static final String FEED_URL = "https://36kr.com/feed";

    public ThirtySixKrRoute(FetchClient fetchClient) {
        super(fetchClient);
    }

    @Override
    protected String feedUrl(RouteContext context) {
        return FEED_URL;
    }
}
