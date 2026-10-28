package app.routes.github;

import app.core.FetchClient;
import app.core.RouteContext;
import app.routes.GenericRssRoute;
import app.store.FeedStore;

public class ComfyUiReleasesRoute extends GenericRssRoute {
    public ComfyUiReleasesRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://github.com/Comfy-Org/ComfyUI/releases.atom";
    }
}
