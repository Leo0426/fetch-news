package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class HuggingFaceBlogRoute extends GenericRssRoute {
    public HuggingFaceBlogRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://huggingface.co/blog/feed.xml";
    }
}
