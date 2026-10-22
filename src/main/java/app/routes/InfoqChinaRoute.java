package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class InfoqChinaRoute extends GenericRssRoute {
    public InfoqChinaRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.infoq.cn/feed";
    }
}
