package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class RaspberryPiRoute extends GenericRssRoute {
    public RaspberryPiRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.raspberrypi.com/news/feed/";
    }
}
