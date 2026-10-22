package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class NoaaNewsRoute extends GenericRssRoute {
    public NoaaNewsRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.noaa.gov/rss.xml";
    }
}
