package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class NytChineseRoute extends GenericRssRoute {
    public NytChineseRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://cn.nytimes.com/rss/";
    }
}
