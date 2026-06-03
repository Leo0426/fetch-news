package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;

/**
 * Route that proxies the Stack Overflow questions feed for a tag.
 *
 * <p>Path: /stackoverflow/tag/:tag
 */
public class StackOverflowRoute extends RssProxyRoute {

    public StackOverflowRoute(FetchClient fetchClient) {
        super(fetchClient);
    }

    @Override
    protected String feedUrl(RouteContext context) {
        String tag = context.pathParam("tag");
        if (tag == null || tag.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "tag is required");
        }
        return "https://stackoverflow.com/feeds/tag/" + tag;
    }
}
