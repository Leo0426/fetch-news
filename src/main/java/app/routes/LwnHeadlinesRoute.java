package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class LwnHeadlinesRoute extends GenericRssRoute {
    public LwnHeadlinesRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://lwn.net/headlines/rss";
    }
}
