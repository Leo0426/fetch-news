package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class GoogleAiBlogRoute extends GenericRssRoute {
    public GoogleAiBlogRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://blog.google/technology/ai/rss/";
    }
}
