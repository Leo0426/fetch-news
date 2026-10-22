package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class GuardianWorldRoute extends GenericRssRoute {
    public GuardianWorldRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext ctx) {
        return "https://www.theguardian.com/world/rss";
    }
}
