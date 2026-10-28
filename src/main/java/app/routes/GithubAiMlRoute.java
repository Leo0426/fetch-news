package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class GithubAiMlRoute extends GenericRssRoute {
    public GithubAiMlRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://github.blog/ai-and-ml/feed/";
    }
}
