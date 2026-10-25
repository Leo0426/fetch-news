package app.routes.twitter;

import app.core.CacheService;
import app.core.CredentialConfigStore;
import app.core.FetchClient;
import com.fasterxml.jackson.databind.ObjectMapper;

public class OpenAiTwitterRoute extends TwitterUserRoute {
    public OpenAiTwitterRoute(FetchClient client, ObjectMapper mapper, CacheService cache,
                               CredentialConfigStore credentials) {
        super(client, mapper, cache, credentials, "OpenAI");
    }
}
