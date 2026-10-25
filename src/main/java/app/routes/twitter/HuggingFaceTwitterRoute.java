package app.routes.twitter;

import app.core.CacheService;
import app.core.CredentialConfigStore;
import app.core.FetchClient;
import com.fasterxml.jackson.databind.ObjectMapper;

public class HuggingFaceTwitterRoute extends TwitterUserRoute {
    public HuggingFaceTwitterRoute(FetchClient client, ObjectMapper mapper, CacheService cache,
                               CredentialConfigStore credentials) {
        super(client, mapper, cache, credentials, "huggingface");
    }
}
