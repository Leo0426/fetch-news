package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class EdSurgeNewsRoute extends GenericRssRoute {
    public EdSurgeNewsRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.edsurge.com/articles_rss";
    }
}
