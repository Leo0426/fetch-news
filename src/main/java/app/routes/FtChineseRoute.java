package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class FtChineseRoute extends GenericRssRoute {
    public FtChineseRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.ftchinese.com/rss/news";
    }
}
