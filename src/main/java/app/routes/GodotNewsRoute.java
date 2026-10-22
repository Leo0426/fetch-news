package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class GodotNewsRoute extends GenericRssRoute {
    public GodotNewsRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://godotengine.org/atom.xml";
    }
}
