package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class GithubChangelogRoute extends GenericRssRoute {
    public GithubChangelogRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://github.blog/changelog/feed/";
    }
}
