package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class FactorioBlogRoute extends GenericRssRoute {
    public FactorioBlogRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://factorio.com/blog/rss";
    }
}
