package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class NvidiaTechBlogRoute extends GenericRssRoute {
    public NvidiaTechBlogRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://developer.nvidia.com/blog/feed";
    }
}
