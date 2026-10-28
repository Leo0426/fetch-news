package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class PragmaticEngineerRoute extends GenericRssRoute {
    public PragmaticEngineerRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://newsletter.pragmaticengineer.com/feed";
    }
}
