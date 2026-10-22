package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class UnNewsChineseRoute extends GenericRssRoute {
    public UnNewsChineseRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext ctx) {
        return "https://news.un.org/feed/subscribe/zh/news/all/rss.xml";
    }
}
