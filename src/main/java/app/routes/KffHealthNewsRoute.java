package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class KffHealthNewsRoute extends GenericRssRoute {
    public KffHealthNewsRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://kffhealthnews.org/feed/";
    }
}
