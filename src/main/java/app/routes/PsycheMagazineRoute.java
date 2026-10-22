package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class PsycheMagazineRoute extends GenericRssRoute {
    public PsycheMagazineRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://psyche.co/feed.rss";
    }
}
