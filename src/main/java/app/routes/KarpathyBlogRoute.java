package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class KarpathyBlogRoute extends GenericRssRoute {
    public KarpathyBlogRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://karpathy.bearblog.dev/feed/";
    }
}
