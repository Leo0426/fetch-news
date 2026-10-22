package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class GoogleResearchRoute extends GenericRssRoute {
    public GoogleResearchRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://research.google/blog/rss/";
    }
}
