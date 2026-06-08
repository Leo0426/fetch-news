package app.routes;

import app.CredentialConfigStore;
import app.core.CacheService;
import app.core.FetchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared Twitter/X helpers: auth headers, GraphQL requests, tweet parsing.
 *
 * <p>Set {@code TWITTER_COOKIE} env var to the full cookie string from browser
 * DevTools (must include {@code auth_token} and {@code ct0}).
 *
 * <p>GraphQL query IDs are resolved dynamically from Twitter's JS bundle and
 * cached for 24 h; hardcoded fallback IDs are used if resolution fails.
 */
class TwitterHelper {

    // Fixed bearer token (public, same for all Twitter web clients)
    static final String BEARER =
            "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs" +
            "%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA";

    private static final String BASE_URL = "https://x.com/i/api/graphql";

    // Hardcoded fallback GQL query IDs (last known working)
    private static final Map<String, String> FALLBACK_IDS = Map.of(
            "UserByScreenName",         "Yka-W8dz7RaEuQNkroPkYw",
            "UserByRestId",             "Qw77dDjp9xCpUY-AXwt-yQ",
            "UserTweets",               "E3opETHurmVJflFsUBVuUQ",
            "UserTweetsAndReplies",     "bt4TKuFz4T7Ckk-VvQVSow"
    );

    // GQL feature flags for user-lookup endpoints
    private static final String FEATURES_USER =
            "{\"hidden_profile_subscriptions_enabled\":true," +
            "\"rweb_tipjar_consumption_enabled\":true," +
            "\"responsive_web_graphql_exclude_directive_enabled\":true," +
            "\"verified_phone_label_enabled\":false," +
            "\"subscriptions_verification_info_is_identity_verified_enabled\":true," +
            "\"subscriptions_verification_info_verified_since_enabled\":true," +
            "\"highlights_tweets_tab_ui_enabled\":true," +
            "\"responsive_web_twitter_article_notes_tab_enabled\":true," +
            "\"subscriptions_feature_can_gift_premium\":true," +
            "\"creator_subscriptions_tweet_preview_api_enabled\":true," +
            "\"responsive_web_graphql_skip_user_profile_image_extensions_enabled\":false," +
            "\"responsive_web_graphql_timeline_navigation_enabled\":true}";

    // GQL feature flags for feed/timeline endpoints
    static final String FEATURES_FEED =
            "{\"rweb_tipjar_consumption_enabled\":true," +
            "\"responsive_web_graphql_exclude_directive_enabled\":true," +
            "\"verified_phone_label_enabled\":false," +
            "\"creator_subscriptions_tweet_preview_api_enabled\":true," +
            "\"responsive_web_graphql_timeline_navigation_enabled\":true," +
            "\"responsive_web_graphql_skip_user_profile_image_extensions_enabled\":false," +
            "\"communities_web_enable_tweet_community_results_fetch\":true," +
            "\"c9s_tweet_anatomy_moderator_badge_enabled\":true," +
            "\"articles_preview_enabled\":true," +
            "\"responsive_web_edit_tweet_api_enabled\":true," +
            "\"graphql_is_translatable_rweb_tweet_is_translatable_enabled\":true," +
            "\"view_counts_everywhere_api_enabled\":true," +
            "\"longform_notetweets_consumption_enabled\":true," +
            "\"responsive_web_twitter_article_tweet_consumption_enabled\":true," +
            "\"tweet_awards_web_tipping_enabled\":false," +
            "\"creator_subscriptions_quote_tweet_preview_enabled\":false," +
            "\"freedom_of_speech_not_reach_fetch_enabled\":true," +
            "\"standardized_nudges_misinfo\":true," +
            "\"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled\":true," +
            "\"rweb_video_timestamps_enabled\":true," +
            "\"longform_notetweets_rich_text_read_enabled\":true," +
            "\"longform_notetweets_inline_media_enabled\":true," +
            "\"responsive_web_enhance_cards_enabled\":false}";

    private static final DateTimeFormatter TWITTER_DATE =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
    private static final Pattern MAIN_JS =
            Pattern.compile("/client-web/main\\.([a-z0-9]+)\\.");
    private static final Pattern GQL_ID =
            Pattern.compile("queryId:\"([^\"]+)\".+?operationName:\"([^\"]+)\"");

    private final FetchClient fetchClient;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;
    private final CredentialConfigStore credStore;

    TwitterHelper(FetchClient fetchClient, ObjectMapper objectMapper,
                  CacheService cacheService, CredentialConfigStore credStore) {
        this.fetchClient  = fetchClient;
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
        this.credStore    = credStore;
    }

    boolean hasCookie() {
        String c = credStore.getTwitterCookie();
        return c != null && !c.isBlank();
    }

    /** Builds auth headers using the current Twitter cookie from the credential store. */
    private Map<String, String> authHeaders() {
        String cookie = credStore.getTwitterCookie();
        var h = new HashMap<String, String>();
        h.put("authorization",              BEARER);
        h.put("x-twitter-active-user",      "yes");
        h.put("x-twitter-client-language",  "en");
        h.put("content-type",               "application/json");
        h.put("accept-language",            "en-US,en;q=0.9");
        h.put("referer",                    "https://x.com/");
        if (cookie != null && !cookie.isBlank()) {
            h.put("cookie",               cookie);
            h.put("x-csrf-token",         extractCt0(cookie));
            h.put("x-twitter-auth-type",  "OAuth2Session");
        }
        return Collections.unmodifiableMap(h);
    }

    // ── GQL request ───────────────────────────────────────────────────────────

    /** Looks up a user by screen name; returns the result node. */
    JsonNode getUserByScreenName(String screenName) throws Exception {
        String vars = "{\"screen_name\":\"" + screenName + "\",\"withSafetyModeUserFields\":true}";
        String url  = gqlUrl("UserByScreenName", vars, FEATURES_USER);
        JsonNode root = get(url);
        return root.path("data").path("user").path("result");
    }

    /** Looks up a user by numeric REST ID; returns the result node. */
    JsonNode getUserByRestId(String restId) throws Exception {
        String vars = "{\"userId\":\"" + restId + "\",\"withSafetyModeUserFields\":true}";
        String url  = gqlUrl("UserByRestId", vars, FEATURES_USER);
        JsonNode root = get(url);
        return root.path("data").path("user").path("result");
    }

    /** Fetches the timeline entries for UserTweets. */
    List<JsonNode> getUserTweets(String userId, int count) throws Exception {
        String vars = "{\"userId\":\"" + userId + "\",\"count\":" + count +
                ",\"includePromotedContent\":true,\"withQuickPromoteEligibilityTweetFields\":true" +
                ",\"withVoice\":true,\"withV2Timeline\":true}";
        String url  = gqlUrl("UserTweets", vars, FEATURES_FEED);
        JsonNode root = get(url);
        return extractEntries(root.path("data"));
    }

    // ── tweet → legacy list ───────────────────────────────────────────────────

    /** Extracts tweet legacy objects from timeline entries. */
    static List<JsonNode> gatherLegacy(List<JsonNode> entries) {
        var tweets = new ArrayList<JsonNode>();
        for (JsonNode entry : entries) {
            String entryId = entry.path("entryId").asText("");
            if (!entryId.startsWith("tweet-") && !entryId.startsWith("profile-grid-0-tweet-")) continue;
            JsonNode content = entry.has("content") ? entry.path("content") : entry.path("item");
            JsonNode tweetResult = content.path("itemContent").path("tweet_results").path("result");
            if (tweetResult.isMissingNode()) continue;
            // Unwrap tweet wrapper
            JsonNode tweet = tweetResult.has("tweet") ? tweetResult.path("tweet") : tweetResult;
            JsonNode legacy = tweet.path("legacy");
            if (legacy.isMissingNode()) continue;
            // Hydrate user into legacy
            JsonNode userLegacy = tweet.path("core").path("user_results").path("result").path("legacy");
            if (!userLegacy.isMissingNode()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).set("user", userLegacy);
            }
            // Hydrate quoted tweet
            JsonNode quoted = tweet.path("quoted_status_result").path("result");
            if (!quoted.isMissingNode()) {
                JsonNode qTweet   = quoted.has("tweet") ? quoted.path("tweet") : quoted;
                JsonNode qLegacy  = qTweet.path("legacy");
                JsonNode qUser    = qTweet.path("core").path("user_results").path("result").path("legacy");
                if (!qLegacy.isMissingNode()) {
                    if (!qUser.isMissingNode()) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) qLegacy).set("user", qUser);
                    }
                    ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).set("quoted_status", qLegacy);
                }
            }
            // Set id_str from rest_id
            if (legacy.path("id_str").asText("").isBlank() && !tweet.path("rest_id").asText("").isBlank()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) legacy)
                        .put("id_str", tweet.path("rest_id").asText());
            }
            tweets.add(legacy);
        }
        return tweets;
    }

    // ── tweet content builders ────────────────────────────────────────────────

    /** Formats tweet text: expands t.co URLs, removes media placeholders. */
    static String formatText(JsonNode legacy) {
        String text = legacy.path("full_text").asText(legacy.path("text").asText(""));
        String idStr = legacy.path("id_str").asText(legacy.path("conversation_id_str").asText(""));
        // Expand t.co URLs
        for (JsonNode url : legacy.path("entities").path("urls")) {
            String tco      = url.path("url").asText();
            String expanded = url.path("expanded_url").asText();
            if (!tco.isEmpty() && !expanded.isEmpty()) {
                // Drop link pointing back to the tweet itself (truncation artifact)
                text = text.replace(tco, expanded.endsWith(idStr) ? "" : expanded);
            }
        }
        // Remove media t.co placeholders
        for (JsonNode m : legacy.path("extended_entities").path("media")) {
            String mUrl = m.path("url").asText();
            if (!mUrl.isEmpty()) text = text.replace(mUrl, "");
        }
        return text.trim().replace("\n", "<br>");
    }

    /** Renders images and videos from extended_entities into HTML. */
    static String formatMedia(JsonNode legacy) {
        var sb = new StringBuilder();
        for (JsonNode m : legacy.path("extended_entities").path("media")) {
            String type = m.path("type").asText();
            if ("photo".equals(type)) {
                String src = originalImg(m.path("media_url_https").asText());
                if (!src.isEmpty()) sb.append("<img src=\"").append(src).append("\"><br>");
            } else if ("video".equals(type) || "animated_gif".equals(type)) {
                JsonNode best = bestVideoVariant(m.path("video_info").path("variants"));
                if (best != null) {
                    String poster = originalImg(m.path("media_url_https").asText());
                    String extra  = "animated_gif".equals(type) ? " autoplay loop muted playsinline" : "";
                    sb.append("<video src=\"").append(best.path("url").asText())
                      .append("\" controls poster=\"").append(poster).append("\"")
                      .append(extra).append("></video><br>");
                }
            }
        }
        return sb.toString();
    }

    /** Builds a blockquote for a quoted tweet. */
    static String formatQuote(JsonNode quoted) {
        if (quoted.isMissingNode() || quoted.isNull()) return "";
        String author = quoted.path("user").path("name").asText(
                quoted.path("user").path("screen_name").asText(""));
        String text   = formatText(quoted);
        String media  = formatMedia(quoted);
        var sb = new StringBuilder("<blockquote>");
        if (!author.isEmpty()) sb.append("<b>@").append(escapeHtml(author)).append("</b>: ");
        sb.append(text).append(media).append("</blockquote>");
        return sb.toString();
    }

    static Instant parseTwitterDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ZonedDateTime.parse(value, TWITTER_DATE).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private JsonNode get(String url) throws Exception {
        String body = fetchClient.get(url, authHeaders());
        return objectMapper.readTree(body);
    }

    private String gqlUrl(String operation, String variables, String features) throws Exception {
        String qid = resolvedId(operation);
        return BASE_URL + "/" + qid + "/" + operation
                + "?variables=" + enc(variables)
                + "&features="  + enc(features);
    }

    private String resolvedId(String operation) throws Exception {
        try {
            Map<String, String> ids = castToStringMap(cacheService.getDetailPage(
                    "twitter:gql-ids", Duration.ofHours(24), this::fetchGqlIds));
            String id = ids.get(operation);
            return id != null ? id : FALLBACK_IDS.getOrDefault(operation, operation);
        } catch (Exception ignored) {
            return FALLBACK_IDS.getOrDefault(operation, operation);
        }
    }

    private Map<String, String> fetchGqlIds() throws Exception {
        // Fetch Twitter home page → find main.{hash}.js URL → extract queryId/operationName pairs
        String html = fetchClient.get("https://x.com",
                Map.of("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"));
        var m = MAIN_JS.matcher(html);
        if (!m.find()) return new HashMap<>(FALLBACK_IDS);

        String jsUrl = "https://abs.twimg.com/responsive-web/client-web/main." + m.group(1) + ".js";
        String js = fetchClient.get(jsUrl, Map.of());
        var ids = new HashMap<>(FALLBACK_IDS);
        var gm = GQL_ID.matcher(js);
        while (gm.find()) {
            String name = gm.group(2);
            if (FALLBACK_IDS.containsKey(name)) ids.put(name, gm.group(1));
        }
        return ids;
    }

    private List<JsonNode> extractEntries(JsonNode data) {
        // Try multiple response paths for user timeline
        JsonNode userResult = data.path("user").path("result");
        JsonNode tl = userResult.path("timeline_v2").path("timeline");
        if (tl.isMissingNode()) tl = userResult.path("timeline").path("timeline");
        if (tl.isMissingNode()) tl = userResult.path("timeline").path("timeline_v2");

        var entries = new ArrayList<JsonNode>();
        for (JsonNode instr : tl.path("instructions")) {
            if ("TimelineAddEntries".equals(instr.path("type").asText())) {
                for (JsonNode e : instr.path("entries")) entries.add(e);
            }
        }
        return entries;
    }

    private static String originalImg(String url) {
        if (url == null || url.isBlank()) return url;
        // https://pbs.twimg.com/media/XXX.jpg → XXX?format=jpg&name=orig
        var m = Pattern.compile(
                "^(https?://\\w+\\.twimg\\.com/media/[^/:]+)\\.(jpg|jpeg|gif|png|webp)(:\\w+)?$",
                Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.matches()) {
            String fmt = "jpeg".equals(m.group(2)) ? "jpg" : m.group(2);
            return m.group(1) + "?format=" + fmt + "&name=orig";
        }
        return url;
    }

    private static JsonNode bestVideoVariant(JsonNode variants) {
        JsonNode best = null;
        int bestBitrate = -1;
        for (JsonNode v : variants) {
            int br = v.path("bitrate").asInt(-1);
            if (br > bestBitrate) {
                bestBitrate = br;
                best = v;
            }
        }
        return best;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String extractCt0(String cookieStr) {
        for (String part : cookieStr.split(";")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length == 2 && "ct0".equals(kv[0].strip())) return kv[1].strip();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> castToStringMap(Object o) {
        return (Map<String, String>) o;
    }
}
