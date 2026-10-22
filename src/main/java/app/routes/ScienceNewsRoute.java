package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class ScienceNewsRoute extends GenericRssRoute {
    public ScienceNewsRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.sciencenews.org/feed";
    }
}
