package app.routes.bilibili;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Route for a Bilibili UP主's latest uploaded videos.
 *
 * <p>Path: /bilibili/user/video/:uid
 *
 * <p>Uses the WBI-signed {@code /x/space/wbi/arc/search} endpoint.
 * Set {@code BILIBILI_COOKIE} env var (buvid3=...; SESSDATA=...) to
 * avoid rate-limiting.
 */
public class BilibiliVideoRoute implements RouteHandler {

    private static final String API_URL =
            "https://api.bilibili.com/x/space/wbi/arc/search";
    private static final String BASE_PARAMS =
            "tid=0&pn=1&ps=30&keyword=&order=pubdate&platform=web" +
            "&web_location=1550101&order_avoided=true";

    private final BilibiliHelper helper;
    private final ObjectMapper objectMapper;

    public BilibiliVideoRoute(FetchClient fetchClient, ObjectMapper objectMapper,
                               CacheService cacheService,
                                         CredentialConfigStore credStore) {
        this.helper       = new BilibiliHelper(fetchClient, objectMapper, cacheService, credStore);
        this.objectMapper = objectMapper;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String uid = context.pathParam("uid");
        if (uid == null || uid.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "uid is required");
        }

        String params  = helper.wbiSign("mid=" + uid + "&" + BASE_PARAMS);
        String url     = API_URL + "?" + params;
        String json    = helper.get(url, "https://space.bilibili.com/" + uid);

        try {
            JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED,
                        "Bilibili API error " + code + ": " + root.path("message").asText("unknown"));
            }
            JsonNode vlist = root.path("data").path("list").path("vlist");
            if (!vlist.isArray()) {
                throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT,
                        "unexpected Bilibili API response");
            }
            List<FeedItem> items = new ArrayList<>();
            for (JsonNode v : vlist) {
                FeedItem item = toItem(v);
                if (item != null) items.add(item);
            }
            if (items.isEmpty()) throw new RouteException(RouteError.EMPTY_FEED);

            String author = items.getFirst().author();
            String title  = author != null ? author + " 的 Bilibili 视频" : "Bilibili UID " + uid;
            return new Feed(title,
                    "https://space.bilibili.com/" + uid,
                    "Bilibili UP主 UID " + uid + " 的最新投稿",
                    items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    private FeedItem toItem(JsonNode v) {
        String bvid  = v.path("bvid").asText(null);
        String title = v.path("title").asText(null);
        if (title == null || bvid == null) return null;

        String author  = BilibiliHelper.nullIfBlank(v.path("author").asText(null));
        String desc    = v.path("description").asText(null);
        String pic     = v.path("pic").asText(null);
        long   created = v.path("created").asLong(0);
        String link    = "https://www.bilibili.com/video/" + bvid;

        var sb = new StringBuilder();
        if (pic != null && !pic.isBlank()) {
            sb.append("<img src=\"").append(pic).append("\"><br>");
        }
        if (desc != null && !desc.isBlank()) {
            sb.append("<p>").append(BilibiliHelper.escapeHtml(desc)).append("</p>");
        }
        String description = sb.isEmpty() ? null : sb.toString();

        return new FeedItem(title, link, description,
                created > 0 ? Instant.ofEpochSecond(created) : null,
                author, List.of());
    }
}
