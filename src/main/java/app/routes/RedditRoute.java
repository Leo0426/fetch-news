package app.routes;

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

/**
 * Route that returns Reddit posts for any subreddit.
 *
 * <p>Path: /reddit/r/:subreddit — uses Reddit's public JSON API.
 */
public class RedditRoute implements RouteHandler {
    private static final String API_URL =
            "https://www.reddit.com/r/%s/hot.json?limit=25&raw_json=1";

    private final FetchClient fetchClient;
    private final ObjectMapper objectMapper;

    public RedditRoute(FetchClient fetchClient, ObjectMapper objectMapper) {
        this.fetchClient = fetchClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String subreddit = context.pathParam("subreddit");
        if (subreddit == null || subreddit.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "subreddit is required");
        }
        String url = String.format(API_URL, subreddit);
        String json = fetchClient.get(url);
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode children = root.path("data").path("children");
            if (!children.isArray()) {
                throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT, "unexpected Reddit API response");
            }
            List<FeedItem> items = new ArrayList<>();
            for (JsonNode child : children) {
                FeedItem item = toItem(child.path("data"), subreddit);
                if (item != null) items.add(item);
            }
            if (items.isEmpty()) {
                throw new RouteException(RouteError.EMPTY_FEED);
            }
            return new Feed(
                    "r/" + subreddit,
                    "https://www.reddit.com/r/" + subreddit,
                    "Hot posts from r/" + subreddit,
                    items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    private FeedItem toItem(JsonNode data, String subreddit) {
        String title = data.path("title").asText(null);
        if (title == null) return null;

        String permalink = "https://www.reddit.com" + data.path("permalink").asText("");
        String url = data.path("url").asText(null);
        boolean isSelf = data.path("is_self").asBoolean(false);
        String link = (isSelf || url == null || url.isBlank()) ? permalink : url;

        String author = data.path("author").asText(null);
        int score = data.path("score").asInt(0);
        int numComments = data.path("num_comments").asInt(0);
        long createdUtc = data.path("created_utc").asLong(0);
        String selftext = data.path("selftext").asText(null);

        String desc = buildDescription(score, numComments, permalink, selftext, isSelf);
        Instant pubDate = createdUtc > 0 ? Instant.ofEpochSecond(createdUtc) : null;
        return new FeedItem(title, link, desc, pubDate, author, List.of());
    }

    private String buildDescription(int score, int comments, String permalink, String selftext, boolean isSelf) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p>").append(score).append(" points | <a href=\"").append(permalink)
          .append("\">").append(comments).append(" comments</a></p>");
        if (isSelf && selftext != null && !selftext.isBlank() && !selftext.equals("[deleted]")) {
            sb.append("<p>").append(escapeHtml(selftext.length() > 500 ? selftext.substring(0, 500) + "…" : selftext))
              .append("</p>");
        }
        return sb.toString();
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
