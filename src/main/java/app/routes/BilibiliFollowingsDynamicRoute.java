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
 * Route for a logged-in Bilibili user's following feed (all dynamic types).
 *
 * <p>Path: /bilibili/followings/dynamic/:uid
 *
 * <p>{@code :uid} is the <em>logged-in user's</em> UID. Requires
 * {@code BILIBILI_COOKIE_{uid}} env var containing the full cookie string
 * obtained from browser DevTools while visiting the Bilibili dynamic API.
 *
 * <p>Uses the legacy {@code dynamic_svr} API which returns cards for all
 * content types (video, article, image post, text, repost, etc.).
 */
public class BilibiliFollowingsDynamicRoute implements RouteHandler {

    private static final String API =
            "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/dynamic_new" +
            "?uid=%s&type_list=268435455";

    private final BilibiliHelper helper;
    private final ObjectMapper objectMapper;

    public BilibiliFollowingsDynamicRoute(FetchClient fetchClient, ObjectMapper objectMapper,
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
                    "BILIBILI_COOKIE_" + uid + " env var is required (full cookie string from browser)");
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
            if (!cards.isArray() || cards.isEmpty()) {
                throw new RouteException(RouteError.EMPTY_FEED);
            }

            List<FeedItem> items = new ArrayList<>();
            for (JsonNode card : cards) {
                FeedItem item = toItem(card);
                if (item != null) items.add(item);
            }
            if (items.isEmpty()) throw new RouteException(RouteError.EMPTY_FEED);

            return new Feed(
                    "UID " + uid + " 的关注动态",
                    "https://t.bilibili.com",
                    "Bilibili UID " + uid + " 的关注全部动态",
                    items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    private FeedItem toItem(JsonNode card) {
        try {
            JsonNode desc = card.path("desc");
            int type = desc.path("type").asInt(0);
            String dynamicIdStr = desc.path("dynamic_id_str").asText(null);
            String author = desc.path("user_profile").path("info").path("uname").asText(null);
            long ts = desc.path("timestamp").asLong(0);
            Instant pubDate = ts > 0 ? Instant.ofEpochSecond(ts) : null;

            JsonNode cardData = objectMapper.readTree(card.path("card").asText("{}"));
            return buildItem(type, cardData, desc, dynamicIdStr, author, pubDate);
        } catch (Exception e) {
            return null;
        }
    }

    private FeedItem buildItem(int type, JsonNode data, JsonNode desc,
                                String dynamicId, String author, Instant pubDate) {
        String link = dynamicId != null ? "https://t.bilibili.com/" + dynamicId : null;
        String title, description;

        switch (type) {
            case 8 -> {   // 视频
                String bvid = desc.path("bvid").asText(null);
                if (bvid != null) link = "https://www.bilibili.com/video/" + bvid;
                title = data.path("title").asText("视频动态");
                var sb = new StringBuilder();
                String pic = data.path("pic").asText(null);
                if (pic != null) sb.append("<img src=\"").append(pic).append("\"><br>");
                String videoDesc = data.path("desc").asText(null);
                if (videoDesc != null && !videoDesc.isBlank())
                    sb.append("<p>").append(escapeHtml(videoDesc)).append("</p>");
                description = sb.isEmpty() ? null : sb.toString();
            }
            case 64 -> {  // 专栏
                String cvid = data.path("id").asText(null);
                if (cvid != null) link = "https://www.bilibili.com/read/cv" + cvid;
                title = data.path("title").asText("专栏");
                var sb = new StringBuilder();
                for (JsonNode img : data.path("image_urls")) {
                    sb.append("<img src=\"").append(img.asText()).append("\"><br>");
                }
                String summary = data.path("summary").asText(null);
                if (summary != null && !summary.isBlank())
                    sb.append("<p>").append(escapeHtml(summary)).append("</p>");
                description = sb.isEmpty() ? null : sb.toString();
            }
            case 2 -> {   // 图文
                JsonNode item = data.path("item");
                title = truncate(item.path("description").asText("图文动态"), 60);
                var sb = new StringBuilder();
                String text = item.path("description").asText(null);
                if (text != null && !text.isBlank())
                    sb.append("<p>").append(escapeHtml(text)).append("</p>");
                for (JsonNode pic : item.path("pictures"))
                    sb.append("<img src=\"").append(pic.path("img_src").asText()).append("\"><br>");
                description = sb.isEmpty() ? null : sb.toString();
            }
            case 4 -> {   // 纯文字
                String text = data.path("item").path("content").asText(null);
                title = truncate(text, 60);
                description = text != null ? "<p>" + escapeHtml(text) + "</p>" : null;
            }
            case 1 -> {   // 转发
                JsonNode item = data.path("item");
                String repostText = item.path("content").asText(null);
                title = "转发：" + truncate(repostText, 50);
                String originStr = item.path("origin").asText(null);
                var sb = new StringBuilder();
                if (repostText != null) sb.append("<p>").append(escapeHtml(repostText)).append("</p>");
                if (originStr != null) {
                    try {
                        JsonNode origin = objectMapper.readTree(originStr);
                        String originTitle = firstNonBlank(
                                origin.path("title").asText(null),
                                origin.path("item").path("description").asText(null),
                                origin.path("item").path("content").asText(null));
                        if (originTitle != null)
                            sb.append("<blockquote>").append(escapeHtml(originTitle)).append("</blockquote>");
                    } catch (Exception ignored) {}
                }
                description = sb.isEmpty() ? null : sb.toString();
            }
            default -> {
                String text = firstNonBlank(
                        data.path("title").asText(null),
                        data.path("item").path("content").asText(null),
                        data.path("item").path("description").asText(null));
                title = truncate(text != null ? text : "动态", 60);
                description = text != null ? "<p>" + escapeHtml(text) + "</p>" : null;
            }
        }

        if (link == null) return null;
        return new FeedItem(title, link, description, pubDate,
                BilibiliHelper.nullIfBlank(author), List.of());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "动态";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String escapeHtml(String s) { return BilibiliHelper.escapeHtml(s); }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
