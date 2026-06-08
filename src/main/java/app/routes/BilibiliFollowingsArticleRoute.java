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

/**
 * Route for a logged-in Bilibili user's following article (专栏) feed.
 *
 * <p>Path: /bilibili/followings/article/:uid
 *
 * <p>Requires {@code BILIBILI_COOKIE_{uid}} (SESSDATA field is sufficient).
 */
public class BilibiliFollowingsArticleRoute implements RouteHandler {

    private static final String API =
            "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/dynamic_new" +
            "?uid=%s&type=64";

    private final BilibiliHelper helper;
    private final ObjectMapper objectMapper;

    public BilibiliFollowingsArticleRoute(FetchClient fetchClient, ObjectMapper objectMapper,
                                           CacheService cacheService) {
        this.helper       = new BilibiliHelper(fetchClient, objectMapper, cacheService);
        this.objectMapper = objectMapper;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String uid = context.pathParam("uid");
        if (uid == null || uid.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "uid is required");
        }
        if (!BilibiliHelper.hasCookieForUid(uid)) {
            throw new RouteException(RouteError.INVALID_PARAMETER,
                    "BILIBILI_COOKIE_" + uid + " env var is required (SESSDATA is sufficient)");
        }

        String url  = String.format(API, uid);
        String json = helper.getForUid(url, uid, "https://space.bilibili.com/" + uid + "/");

        try {
            JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(-1);
            if (code == -6) {
                throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED,
                        "BILIBILI_COOKIE_" + uid + " 已过期，请重新获取");
            }
            if (code != 0) {
                throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED,
                        "Bilibili API error " + code + ": " + root.path("message").asText());
            }

            JsonNode cards = root.path("data").path("cards");
            if (!cards.isArray() || cards.isEmpty()) throw new RouteException(RouteError.EMPTY_FEED);

            List<FeedItem> items = new ArrayList<>();
            for (JsonNode card : cards) {
                FeedItem item = toItem(card);
                if (item != null) items.add(item);
            }
            if (items.isEmpty()) throw new RouteException(RouteError.EMPTY_FEED);

            return new Feed(
                    "UID " + uid + " 的关注专栏动态",
                    "https://t.bilibili.com/?tab=64",
                    "Bilibili UID " + uid + " 关注的 UP 主最新专栏",
                    items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    private FeedItem toItem(JsonNode card) {
        try {
            JsonNode desc     = card.path("desc");
            String author     = desc.path("user_profile").path("info").path("uname").asText(null);
            JsonNode cardData = objectMapper.readTree(card.path("card").asText("{}"));

            String cvid    = cardData.path("id").asText(null);
            String title   = cardData.path("title").asText(null);
            if (title == null || cvid == null) return null;

            long publishTime = cardData.path("publish_time").asLong(0);
            String summary   = cardData.path("summary").asText(null);
            String link      = "https://www.bilibili.com/read/cv" + cvid;

            var sb = new StringBuilder();
            for (JsonNode img : cardData.path("image_urls"))
                sb.append("<img src=\"").append(img.asText()).append("\"><br>");
            if (summary != null && !summary.isBlank())
                sb.append("<p>").append(BilibiliHelper.escapeHtml(summary)).append("</p>");

            return new FeedItem(title, link,
                    sb.isEmpty() ? null : sb.toString(),
                    publishTime > 0 ? Instant.ofEpochSecond(publishTime) : null,
                    BilibiliHelper.nullIfBlank(author), List.of());
        } catch (Exception e) {
            return null;
        }
    }
}
