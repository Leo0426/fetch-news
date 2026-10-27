package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class BairBlogRoute extends GenericRssRoute {
    public BairBlogRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://bair.berkeley.edu/blog/feed.xml";
    }
}
