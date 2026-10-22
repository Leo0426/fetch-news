package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class FedsNotesRoute extends GenericRssRoute {
    public FedsNotesRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext ctx) {
        return "https://www.federalreserve.gov/feeds/feds_notes.xml";
    }
}
