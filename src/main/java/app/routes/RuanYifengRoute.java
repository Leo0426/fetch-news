package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class RuanYifengRoute extends GenericRssRoute {
    public RuanYifengRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.ruanyifeng.com/blog/atom.xml";
    }
}
