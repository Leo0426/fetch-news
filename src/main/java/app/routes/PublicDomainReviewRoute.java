package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class PublicDomainReviewRoute extends GenericRssRoute {
    public PublicDomainReviewRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://publicdomainreview.org/rss.xml";
    }
}
