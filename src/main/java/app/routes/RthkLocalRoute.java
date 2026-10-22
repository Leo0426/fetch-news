package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class RthkLocalRoute extends GenericRssRoute {
    public RthkLocalRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext ctx) {
        return "https://rthk9.rthk.hk/rthk/news/rss/c_expressnews_clocal.xml";
    }
}
