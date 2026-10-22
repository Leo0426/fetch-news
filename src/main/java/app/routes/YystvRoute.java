package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class YystvRoute extends GenericRssRoute {
    public YystvRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.yystv.cn/rss/feed";
    }
}
