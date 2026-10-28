package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class LilianWengRoute extends GenericRssRoute {
    public LilianWengRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://lilianweng.github.io/index.xml";
    }
}
