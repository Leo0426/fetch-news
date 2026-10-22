package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class MartinFowlerRoute extends GenericRssRoute {
    public MartinFowlerRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://martinfowler.com/feed.atom";
    }
}
