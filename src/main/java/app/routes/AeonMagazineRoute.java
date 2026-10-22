package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class AeonMagazineRoute extends GenericRssRoute {
    public AeonMagazineRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext ctx) {
        return "https://aeon.co/feed.rss";
    }
}
