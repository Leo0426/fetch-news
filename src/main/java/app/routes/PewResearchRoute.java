package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class PewResearchRoute extends GenericRssRoute {
    public PewResearchRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.pewresearch.org/publications/feed/";
    }
}
