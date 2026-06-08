package app.routes;

import app.core.CacheService;
import app.CredentialConfigStore;
import app.core.Feed;
import app.core.FeedItem;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Route for a Twitter/X user's timeline.
 *
 * <p>Path: /twitter/user/:id
 *
 * <p>{@code :id} is a screen name (e.g. {@code elonmusk}) or a unique numeric
 * ID prefixed with {@code +} (e.g. {@code +44196397}).
 *
 * <p>Requires {@code TWITTER_COOKIE} env var containing at minimum
 * {@code auth_token} and {@code ct0} cookies copied from browser DevTools.
 */
public class TwitterUserRoute implements RouteHandler {

    private final TwitterHelper helper;

    public TwitterUserRoute(FetchClient fetchClient, ObjectMapper objectMapper,
                             CacheService cacheService,
                             CredentialConfigStore credStore) {
        this.helper = new TwitterHelper(fetchClient, objectMapper, cacheService, credStore);
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String id = context.pathParam("id");
        if (id == null || id.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "id is required");
        }
        if (!helper.hasCookie()) {
            throw new RouteException(RouteError.INVALID_PARAMETER,
                    "TWITTER_COOKIE env var is required (set auth_token + ct0 from browser)");
        }

        try {
            // Resolve user
            JsonNode userResult = id.startsWith("+")
                    ? helper.getUserByRestId(id.substring(1))
                    : helper.getUserByScreenName(id);

            JsonNode userLegacy = userResult.path("legacy");
            if (userLegacy.isMissingNode()) {
                throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT, "user not found: " + id);
            }

            String userId     = userResult.path("rest_id").asText();
            String screenName = userLegacy.path("screen_name").asText(id);
            String userDesc   = userLegacy.path("description").asText(null);

            // Fetch tweets
            List<JsonNode> entries = helper.getUserTweets(userId, 20);
            List<JsonNode> legacies = TwitterHelper.gatherLegacy(entries);

            if (legacies.isEmpty()) {
                throw new RouteException(RouteError.EMPTY_FEED);
            }

            List<FeedItem> items = new ArrayList<>();
            for (JsonNode legacy : legacies) {
                FeedItem item = toItem(legacy, screenName);
                if (item != null) items.add(item);
            }
            if (items.isEmpty()) throw new RouteException(RouteError.EMPTY_FEED);

            return new Feed(
                    "@" + screenName + " 的推文",
                    "https://x.com/" + screenName,
                    userDesc,
                    items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    private FeedItem toItem(JsonNode legacy, String defaultScreenName) {
        // For retweets, show the retweeted content with an RT prefix in the title
        JsonNode rtResult   = legacy.path("retweeted_status_result").path("result");
        JsonNode rtTweet    = rtResult.has("tweet") ? rtResult.path("tweet") : rtResult;
        JsonNode rtLegacy   = rtTweet.path("legacy");
        boolean isRetweet   = !rtLegacy.isMissingNode();

        JsonNode body = isRetweet ? rtLegacy : legacy;
        String text   = TwitterHelper.formatText(body);
        String media  = TwitterHelper.formatMedia(body);
        String quote  = TwitterHelper.formatQuote(body.path("quoted_status"));

        // Hydrate RT user if needed
        if (isRetweet) {
            JsonNode rtUser = rtTweet.path("core").path("user_results").path("result").path("legacy");
            if (!rtUser.isMissingNode()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) rtLegacy).set("user", rtUser);
            }
        }

        // Build description
        String description = (text.isBlank() && media.isEmpty() && quote.isEmpty())
                ? null
                : (text.isBlank() ? "" : "<p>" + text + "</p>") + media + quote;

        // Build title
        String bodyUser = body.path("user").path("name")
                              .asText(body.path("user").path("screen_name").asText(""));
        String rawTitle = text.replace("<br>", " ");
        String title;
        if (isRetweet) {
            String rtUserName = TwitterHelper.nullIfBlank(bodyUser);
            title = "RT " + (rtUserName != null ? "@" + rtUserName + ": " : "") +
                    truncate(rawTitle, 100);
        } else if (!legacy.path("in_reply_to_screen_name").asText("").isBlank()) {
            title = "Re " + truncate(rawTitle, 100);
        } else {
            title = truncate(rawTitle.isBlank() ? "(media)" : rawTitle, 100);
        }

        // Tweet link
        String tweetUser = legacy.path("user").path("screen_name").asText(defaultScreenName);
        String idStr     = legacy.path("id_str").asText(legacy.path("conversation_id_str").asText(""));
        if (idStr.isBlank()) return null;
        String link = "https://x.com/" + tweetUser + "/status/" + idStr;

        // Author
        String author = TwitterHelper.nullIfBlank(legacy.path("user").path("name").asText(null));

        // Categories (hashtags)
        var categories = new ArrayList<String>();
        for (JsonNode ht : legacy.path("entities").path("hashtags")) {
            String tag = ht.path("text").asText(null);
            if (tag != null && !tag.isBlank()) categories.add(tag);
        }

        return new FeedItem(
                title, link, description,
                TwitterHelper.parseTwitterDate(legacy.path("created_at").asText(null)),
                author,
                categories.isEmpty() ? List.of() : categories);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
