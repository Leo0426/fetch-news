package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class MicrosoftResearchRoute extends GenericRssRoute {
    public MicrosoftResearchRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://www.microsoft.com/en-us/research/feed/";
    }
}
