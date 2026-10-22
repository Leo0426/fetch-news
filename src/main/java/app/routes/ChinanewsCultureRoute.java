package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class ChinanewsCultureRoute extends GenericRssRoute {
    public ChinanewsCultureRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.chinanews.com.cn/rss/culture.xml";
    }
}
