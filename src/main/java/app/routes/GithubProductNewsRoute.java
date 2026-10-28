package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class GithubProductNewsRoute extends GenericRssRoute {
    public GithubProductNewsRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://github.blog/news-insights/product-news/feed/";
    }
}
