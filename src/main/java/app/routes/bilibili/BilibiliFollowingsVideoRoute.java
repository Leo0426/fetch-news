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
 * Route for a logged-in Bilibili user's following video feed.
 *
 * <p>Path: /bilibili/followings/video/:uid
 *
 * <p>Requires {@code BILIBILI_COOKIE_{uid}} (SESSDATA field is sufficient).
 */
public class BilibiliFollowingsVideoRoute implements RouteHandler {

    private static final String API =
            "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/dynamic_new" +
            "?uid=%s&type=8";

    private final BilibiliHelper helper;
    private final ObjectMapper objectMapper;

    public BilibiliFollowingsVideoRoute(FetchClient fetchClient, ObjectMapper objectMapper,
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
        if (!helper.hasCookieForUid(uid)) {
            throw new RouteException(RouteError.INVALID_PARAMETER,
                    "BILIBILI_COOKIE_" + uid + " env var is required (SESSDATA is sufficient)");
        }

        String url  = String.format(API, uid);
        String json = helper.getForUid(url, uid, "https://space.bilibili.com/" + uid + "/");

        try {
            JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(-1);
            if (code == -6 || code == 4_100_000) {
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
                    "UID " + uid + " 的关注视频动态",
                    "https://t.bilibili.com/?tab=8",
                    "Bilibili UID " + uid + " 关注的 UP 主最新投稿",
                    items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    private FeedItem toItem(JsonNode card) {
        try {
            JsonNode desc    = card.path("desc");
            String bvid      = desc.path("bvid").asText(null);
            long aid         = desc.path("rid").asLong(0);
            String author    = desc.path("user_profile").path("info").path("uname").asText(null);
            JsonNode cardData = objectMapper.readTree(card.path("card").asText("{}"));

            String title   = cardData.path("title").asText(null);
            if (title == null) return null;
            String pic     = cardData.path("pic").asText(null);
            String vidDesc = cardData.path("desc").asText(null);
            long pubdate   = cardData.path("pubdate").asLong(0);
            String link    = bvid != null
                    ? "https://www.bilibili.com/video/" + bvid
                    : (aid > 0 ? "https://www.bilibili.com/video/av" + aid : null);
            if (link == null) return null;

            var sb = new StringBuilder();
            if (pic != null) sb.append("<img src=\"").append(pic).append("\"><br>");
            if (vidDesc != null && !vidDesc.isBlank())
                sb.append("<p>").append(BilibiliHelper.escapeHtml(vidDesc)).append("</p>");

            return new FeedItem(title, link,
                    sb.isEmpty() ? null : sb.toString(),
                    pubdate > 0 ? Instant.ofEpochSecond(pubdate) : null,
                    BilibiliHelper.nullIfBlank(author), List.of());
        } catch (Exception e) {
            return null;
        }
    }
}
