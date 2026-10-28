package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class ThirtySixKrNewsflashRoute extends GenericRssRoute {
    public ThirtySixKrNewsflashRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.36kr.com/feed-newsflash";
    }
}
