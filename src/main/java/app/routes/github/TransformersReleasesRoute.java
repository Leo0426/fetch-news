package app.routes.github;

import app.core.FetchClient;
import app.core.RouteContext;
import app.routes.GenericRssRoute;
import app.store.FeedStore;

/** Reads the repository's Atom feed rather than the REST API, which shares a 60-per-hour unauthenticated quota. */
public class TransformersReleasesRoute extends GenericRssRoute {
    public TransformersReleasesRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://github.com/huggingface/transformers/releases.atom";
    }
}
