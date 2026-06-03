package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;

/**
 * Route that proxies the arXiv RSS feed for a subject category.
 *
 * <p>Path: /arxiv/:category  (e.g. cs.AI, cs.LG, math.CO)
 */
public class ArxivRoute extends RssProxyRoute {

    public ArxivRoute(FetchClient fetchClient) {
        super(fetchClient);
    }

    @Override
    protected String feedUrl(RouteContext context) {
        String category = context.pathParam("category");
        if (category == null || category.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "category is required");
        }
        return "https://rss.arxiv.org/rss/" + category;
    }
}
