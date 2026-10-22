package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class QuantaMagazineRoute extends GenericRssRoute {
    public QuantaMagazineRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.quantamagazine.org/feed/";
    }
}
