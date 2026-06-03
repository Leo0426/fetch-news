package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;

/**
 * Route that proxies the 博客园 (cnblogs) featured posts RSS feed.
 *
 * <p>Path: /cnblogs/post
 */
public class CnblogsRoute extends RssProxyRoute {

    private static final String FEED_URL = "https://feed.cnblogs.com/blog/sitehome/rss";

    public CnblogsRoute(FetchClient fetchClient) {
        super(fetchClient);
    }

    @Override
    protected String feedUrl(RouteContext context) {
        return FEED_URL;
    }
}
