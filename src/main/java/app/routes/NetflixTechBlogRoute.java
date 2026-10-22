package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class NetflixTechBlogRoute extends GenericRssRoute {
    public NetflixTechBlogRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://netflixtechblog.com/feed";
    }
}
