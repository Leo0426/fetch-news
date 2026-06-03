package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;

/**
 * Route that proxies the mritd.com tech blog RSS feed.
 *
 * <p>Path: /mritd/blog
 */
public class MritdRoute extends RssProxyRoute {

    private static final String FEED_URL = "https://mritd.com/feed.xml";

    public MritdRoute(FetchClient fetchClient) {
        super(fetchClient);
    }

    @Override
    protected String feedUrl(RouteContext context) {
        return FEED_URL;
    }
}
