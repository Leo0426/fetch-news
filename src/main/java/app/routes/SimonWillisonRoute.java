package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class SimonWillisonRoute extends GenericRssRoute {
    public SimonWillisonRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://simonwillison.net/atom/everything/";
    }
}
