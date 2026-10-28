package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class AiHotRoute extends GenericRssRoute {
    public AiHotRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://aihot.news/feed.xml";
    }
}
