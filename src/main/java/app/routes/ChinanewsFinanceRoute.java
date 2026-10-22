package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class ChinanewsFinanceRoute extends GenericRssRoute {
    public ChinanewsFinanceRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.chinanews.com.cn/rss/finance.xml";
    }
}
