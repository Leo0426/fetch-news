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
import java.util.Map;

/**
 * Route that returns recent videos from a Bilibili UP主 (user).
 *
 * <p>Path: /bilibili/user/video/:uid
 */
public class BilibiliVideoRoute implements RouteHandler {
    private static final String API_URL =
            "https://api.bilibili.com/x/space/arc/search?mid=%s&ps=30&pn=1&order=pubdate&jsonp=jsonp";

    private final FetchClient fetchClient;
    private final ObjectMapper objectMapper;
    private final Map<String, String> baseHeaders;

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public BilibiliVideoRoute(FetchClient fetchClient, ObjectMapper objectMapper) {
        this.fetchClient = fetchClient;
        this.objectMapper = objectMapper;
        String cookie = System.getenv("BILIBILI_COOKIE");
        var headers = new java.util.HashMap<String, String>();
        headers.put("User-Agent", BROWSER_UA);
        headers.put("Referer", "https://www.bilibili.com");
        headers.put("Origin", "https://www.bilibili.com");
        if (cookie != null && !cookie.isBlank()) headers.put("Cookie", cookie);
        this.baseHeaders = java.util.Collections.unmodifiableMap(headers);
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String uid = context.pathParam("uid");
        if (uid == null || uid.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "uid is required");
        }
        String url = String.format(API_URL, uid);
        String json = fetchClient.get(url, baseHeaders);
        try {
            JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED,
                        "Bilibili API error: " + root.path("message").asText("unknown"));
            }
            JsonNode vlist = root.path("data").path("list").path("vlist");
            if (!vlist.isArray()) {
                throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT, "unexpected Bilibili API response");
            }
            List<FeedItem> items = new ArrayList<>();
            for (JsonNode v : vlist) {
                FeedItem item = toItem(v, uid);
                if (item != null) items.add(item);
            }
            if (items.isEmpty()) {
                throw new RouteException(RouteError.EMPTY_FEED);
            }
            // use author name from first video if available
            String author = items.getFirst().author();
            String title  = author != null ? author + " 的 Bilibili 视频" : "Bilibili UID " + uid;
            return new Feed(
                    title,
                    "https://space.bilibili.com/" + uid,
                    "Bilibili UP主 UID " + uid + " 的最新视频",
                    items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    private FeedItem toItem(JsonNode v, String uid) {
        String bvid   = v.path("bvid").asText(null);
        String title  = v.path("title").asText(null);
        if (title == null || bvid == null) return null;

        String author = v.path("author").asText(null);
        String desc   = v.path("description").asText(null);
        long created  = v.path("created").asLong(0);
        String link   = "https://www.bilibili.com/video/" + bvid;
        String body   = desc != null && !desc.isBlank()
                ? "<p>" + escapeHtml(desc) + "</p>"
                : null;
        return new FeedItem(title, link, body, created > 0 ? Instant.ofEpochSecond(created) : null, author, List.of());
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
