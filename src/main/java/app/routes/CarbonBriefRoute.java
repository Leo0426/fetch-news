package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class CarbonBriefRoute extends GenericRssRoute {
    public CarbonBriefRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.carbonbrief.org/feed";
    }
}
