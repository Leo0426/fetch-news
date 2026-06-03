package app.routes;

import app.core.CacheService;
import app.core.Feed;
import app.core.FeedItem;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Route that serves Hacker News story feeds via the Firebase API.
 *
 * <p>Path: /hn/:feed — where :feed is one of top, new, best, ask, show, job.
 *
 * <p>Fetches the story ID list then retrieves each story individually.
 * Story detail pages are cached to avoid redundant API calls.
 */
public class HackerNewsRoute implements RouteHandler {
    private static final String LIST_URL =
            "https://hacker-news.firebaseio.com/v0/%sstories.json";
    private static final String ITEM_URL =
            "https://hacker-news.firebaseio.com/v0/item/%s.json";
    private static final int DEFAULT_LIMIT = 30;

    private static final Map<String, String> FEED_LABELS = Map.of(
            "top",  "Top Stories",
            "new",  "Newest",
            "best", "Best Stories",
            "ask",  "Ask HN",
            "show", "Show HN",
            "job",  "Jobs");

    private final FetchClient fetchClient;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    public HackerNewsRoute(FetchClient fetchClient, CacheService cacheService, ObjectMapper objectMapper) {
        this.fetchClient = fetchClient;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String feed = context.pathParam("feed");
        if (feed == null || feed.isBlank()) {
            feed = "top";
        }
        if (!FEED_LABELS.containsKey(feed)) {
            throw new RouteException(RouteError.INVALID_PARAMETER,
                    "unknown feed: " + feed + ". Use one of: " + String.join(", ", FEED_LABELS.keySet()));
        }
        String listUrl = String.format(LIST_URL, feed);
        String json = fetchClient.get(listUrl);
        try {
            JsonNode ids = objectMapper.readTree(json);
            if (!ids.isArray()) {
                throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT, "expected JSON array of story IDs");
            }
            List<FeedItem> items = new ArrayList<>();
            for (JsonNode idNode : ids) {
                if (items.size() >= DEFAULT_LIMIT) break;
                FeedItem item = fetchStory(idNode.asText(), feed, context);
                if (item != null) items.add(item);
            }
            if (items.isEmpty()) {
                throw new RouteException(RouteError.EMPTY_FEED);
            }
            String label = FEED_LABELS.get(feed);
            return new Feed(
                    "Hacker News — " + label,
                    "https://news.ycombinator.com/",
                    label + " from Hacker News",
                    items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    private FeedItem fetchStory(String id, String feed, RouteContext context) throws Exception {
        String storyJson = cacheService.getDetailPage(
                "hn:story:" + id,
                context.detailCacheTtl(),
                () -> fetchClient.get(String.format(ITEM_URL, id)));
        JsonNode story = objectMapper.readTree(storyJson);
        if (story == null || story.isNull()) return null;

        String type = story.path("type").asText("story");
        // for job feed, allow job type; otherwise require story
        if (!"story".equals(type) && !("job".equals(feed) && "job".equals(type))) return null;

        String title = story.path("title").asText(null);
        if (title == null) return null;

        String url = story.path("url").asText(null);
        String by = story.path("by").asText(null);
        long time = story.path("time").asLong(0);
        int score = story.path("score").asInt(0);
        int descendants = story.path("descendants").asInt(0);
        String itemUrl = "https://news.ycombinator.com/item?id=" + id;

        String link = (url != null && !url.isBlank()) ? url : itemUrl;
        String description = "<p>" + score + " points by " + escapeHtml(by != null ? by : "unknown")
                + " | <a href=\"" + itemUrl + "\">" + descendants + " comments</a></p>";
        return new FeedItem(title, link, description, time > 0 ? Instant.ofEpochSecond(time) : null, by, List.of());
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
