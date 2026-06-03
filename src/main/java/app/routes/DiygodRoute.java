package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;

/**
 * Route that proxies DIYGod's personal blog RSS feed (diygod.cc).
 *
 * <p>Path: /diygod/blog
 */
public class DiygodRoute extends RssProxyRoute {

    // diygod.me permanently redirected to diygod.cc
    private static final String FEED_URL = "https://diygod.cc/feed";

    public DiygodRoute(FetchClient fetchClient) {
        super(fetchClient);
    }

    @Override
    protected String feedUrl(RouteContext context) {
        return FEED_URL;
    }
}
