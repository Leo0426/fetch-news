package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class CloudflareBlogRoute extends GenericRssRoute {
    public CloudflareBlogRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://blog.cloudflare.com/rss/";
    }
}
