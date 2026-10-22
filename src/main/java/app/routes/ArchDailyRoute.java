package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class ArchDailyRoute extends GenericRssRoute {
    public ArchDailyRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://feeds.feedburner.com/Archdaily";
    }
}
