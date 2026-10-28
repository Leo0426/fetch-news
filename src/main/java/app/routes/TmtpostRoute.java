package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class TmtpostRoute extends GenericRssRoute {
    public TmtpostRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.tmtpost.com/rss.xml";
    }
}
