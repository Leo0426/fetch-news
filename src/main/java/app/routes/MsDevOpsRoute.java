package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;

/**
 * Route that proxies the Microsoft DevOps Blog RSS feed.
 *
 * <p>Path: /microsoft/devops
 */
public class MsDevOpsRoute extends RssProxyRoute {

    private static final String FEED_URL = "https://devblogs.microsoft.com/devops/feed/";

    public MsDevOpsRoute(FetchClient fetchClient) {
        super(fetchClient);
    }

    @Override
    protected String feedUrl(RouteContext context) {
        return FEED_URL;
    }
}
