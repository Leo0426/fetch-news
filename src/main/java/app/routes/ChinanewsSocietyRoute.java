package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class ChinanewsSocietyRoute extends GenericRssRoute {
    public ChinanewsSocietyRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext ctx) {
        return "https://www.chinanews.com.cn/rss/society.xml";
    }
}
