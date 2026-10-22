package app.routes.github;

import app.core.Feed;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class OllamaReleasesRoute implements RouteHandler {
    private final GithubReleaseRoute delegate;

    public OllamaReleasesRoute(FetchClient client, ObjectMapper mapper) {
        delegate = new GithubReleaseRoute(client, mapper);
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        return delegate.handle(new RouteContext(context.path(),
                Map.of("owner", "ollama", "repo", "ollama"), context.queryParams(),
                context.detailCacheTtl(), context.feedUrl()));
    }
}
