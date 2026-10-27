package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class DeepMindBlogRoute extends GenericRssRoute {
    public DeepMindBlogRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://deepmind.google/blog/rss.xml";
    }
}
