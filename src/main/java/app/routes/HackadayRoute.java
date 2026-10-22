package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class HackadayRoute extends GenericRssRoute {
    public HackadayRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://hackaday.com/blog/feed/";
    }
}
