package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class NprWorldRoute extends GenericRssRoute {
    public NprWorldRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://feeds.npr.org/1004/rss.xml";
    }
}
