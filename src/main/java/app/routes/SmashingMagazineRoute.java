package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class SmashingMagazineRoute extends GenericRssRoute {
    public SmashingMagazineRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext ctx) {
        return "https://www.smashingmagazine.com/feed/";
    }
}
