package app.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.CacheService;
import app.core.CredentialConfig;
import app.core.CredentialConfigStore;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.routes.twitter.AnthropicTwitterRoute;
import app.routes.twitter.GithubTwitterRoute;
import app.routes.twitter.HuggingFaceTwitterRoute;
import app.routes.twitter.KarpathyTwitterRoute;
import app.routes.twitter.OpenAiTwitterRoute;
import app.routes.twitter.SimonWillisonTwitterRoute;
import app.routes.twitter.TwitterUserRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CuratedTwitterRoutesTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fixedAccountsFetchTheirOwnTimelineAndPreserveTweetMetadata() throws Exception {
        var credentials = new CredentialConfigStore(directory.resolve("credentials.json"), mapper);
        credentials.save(new CredentialConfig("", Map.of(), Map.of(), "auth_token=test; ct0=test"));
        List<String> accounts = List.of("OpenAI", "AnthropicAI", "huggingface", "karpathy", "simonw", "github");
        var lookups = new AtomicInteger();
        var timelines = new AtomicInteger();
        FetchClient client = url -> {
            if (url.equals("https://x.com")) return "<html></html>";
            String query = URLDecoder.decode(URI.create(url).getRawQuery(), StandardCharsets.UTF_8);
            String variables = query.substring("variables=".length(), query.indexOf("&features="));
            var params = mapper.readTree(variables);
            if (url.contains("/UserByScreenName?")) {
                String account = accounts.get(lookups.getAndIncrement());
                assertEquals(account, params.path("screen_name").asText());
                return "{\"data\":{\"user\":{\"result\":{\"rest_id\":\"123\",\"legacy\":{"
                        + "\"screen_name\":\"" + account + "\",\"description\":\"Research updates\"}}}}}";
            }
            assertTrue(url.contains("/UserTweets?"), url);
            assertEquals("123", params.path("userId").asText());
            timelines.incrementAndGet();
            var response = mapper.readTree("""
                    {"data":{"user":{"result":{"timeline_v2":{"timeline":{"instructions":[
                      {"type":"TimelineAddEntries","entries":[{"entryId":"tweet-456","content":{
                        "itemContent":{"tweet_results":{"result":{"rest_id":"456","legacy":{
                          "full_text":"A new developer tool with detailed implementation notes for model training, evaluation, deployment and reproducible research workflows", "created_at":"Wed Oct 21 12:30:00 +0000 2026",
                          "entities":{"hashtags":[{"text":"AI"}]}
                        }}}}
                      }}]}
                    ]}}}}}}
                    """);
            var entries = (ArrayNode) response.at("/data/user/result/timeline_v2/timeline/instructions/0/entries");
            entries.add(entries.get(0).deepCopy());
            return response.toString();
        };
        List<TwitterUserRoute> routes = routes(client, credentials);
        for (int i = 0; i < routes.size(); i++) {
            var feed = routes.get(i).handle(new RouteContext("/alias", Map.of("id", "unrelated"), Map.of()));
            assertEquals("https://x.com/" + accounts.get(i), feed.link());
            assertEquals(1, feed.items().size());
            var item = feed.items().getFirst();
            assertEquals("https://x.com/" + accounts.get(i) + "/status/456", item.link());
            assertEquals("A new developer tool with detailed implementation notes for model training, evaluation, deployment and reproducible research workflows", item.title());
            assertEquals(Instant.parse("2026-10-21T12:30:00Z"), item.pubDate());
            assertEquals(List.of("AI"), item.categories());
        }
        assertEquals(6, lookups.get());
        assertEquals(6, timelines.get());
    }

    @Test
    void missingCredentialsFailBeforeNetworkWithSetupGuidance() {
        var credentials = new CredentialConfigStore(directory.resolve("empty.json"), mapper);
        for (var route : routes(url -> { throw new AssertionError("Unexpected fetch"); }, credentials)) {
            var error = assertThrows(RouteException.class,
                    () -> route.handle(new RouteContext("/alias", Map.of(), Map.of())));
            assertEquals(RouteError.INVALID_PARAMETER, error.error());
            assertTrue(error.getMessage().contains("TWITTER_COOKIE"));
        }
    }

    private List<TwitterUserRoute> routes(FetchClient client, CredentialConfigStore credentials) {
        var cache = new CacheService();
        return List.of(new OpenAiTwitterRoute(client, mapper, cache, credentials),
                new AnthropicTwitterRoute(client, mapper, cache, credentials),
                new HuggingFaceTwitterRoute(client, mapper, cache, credentials),
                new KarpathyTwitterRoute(client, mapper, cache, credentials),
                new SimonWillisonTwitterRoute(client, mapper, cache, credentials),
                new GithubTwitterRoute(client, mapper, cache, credentials));
    }
}
