package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class EcbBlogRoute extends GenericRssRoute {
    public EcbBlogRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext ctx) {
        return "https://www.ecb.europa.eu/rss/blog.html";
    }
}
