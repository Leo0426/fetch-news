package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class ArsTechnicaRoute extends GenericRssRoute {
    public ArsTechnicaRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://feeds.arstechnica.com/arstechnica/index";
    }
}
