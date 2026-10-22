package app.routes.twitter;

import app.CredentialConfigStore;
import app.core.CacheService;
import app.core.FetchClient;
import com.fasterxml.jackson.databind.ObjectMapper;

public class GithubTwitterRoute extends TwitterUserRoute {
    public GithubTwitterRoute(FetchClient client, ObjectMapper mapper, CacheService cache,
                               CredentialConfigStore credentials) {
        super(client, mapper, cache, credentials, "github");
    }
}
