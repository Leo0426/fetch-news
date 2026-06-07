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
 * Route for a Bilibili UP主's dynamic feed (动态).
 *
 * <p>Path: /bilibili/user/dynamic/:uid
 *
 * <p>Calls {@code /x/polymer/web-dynamic/v1/feed/space}. Handles the main
 * dynamic types: video (AV), image post (DRAW), text (WORD), article/opus
 * (ARTICLE/OPUS), and repost (FORWARD). Requires {@code BILIBILI_COOKIE}.
 */
public class BilibiliDynamicRoute implements RouteHandler {

    private static final String API_URL =
            "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space" +
            "?offset=&host_mid=%s&platform=web" +
            "&features=itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote";

    private final BilibiliHelper helper;
    private final ObjectMapper objectMapper;

    public BilibiliDynamicRoute(FetchClient fetchClient, ObjectMapper objectMapper,
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

        String url  = String.format(API_URL, uid);
        String json = helper.get(url, "https://space.bilibili.com/" + uid + "/dynamic");

        try {
            JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED,
                        "Bilibili API error " + code + ": " + root.path("message").asText("unknown"));
            }
            JsonNode items = root.path("data").path("items");
            if (!items.isArray()) {
                throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT,
                        "unexpected Bilibili dynamic API response");
            }

            String authorName = null;
            List<FeedItem> feedItems = new ArrayList<>();
            for (JsonNode item : items) {
                if (authorName == null) {
                    authorName = item.path("modules").path("module_author").path("name").asText(null);
                }
                FeedItem fi = toItem(item);
                if (fi != null) feedItems.add(fi);
            }
            if (feedItems.isEmpty()) throw new RouteException(RouteError.EMPTY_FEED);

            String author = BilibiliHelper.nullIfBlank(authorName);
            String name   = author != null ? author : "UID " + uid;
            return new Feed(
                    name + " 的 Bilibili 动态",
                    "https://space.bilibili.com/" + uid + "/dynamic",
                    name + " 的 bilibili 动态",
                    feedItems);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    private FeedItem toItem(JsonNode item) {
        String type   = item.path("type").asText("");
        String idStr  = item.path("id_str").asText(null);
        JsonNode mods = item.path("modules");
        JsonNode auth = mods.path("module_author");
        JsonNode dyn  = mods.path("module_dynamic");

        String author  = BilibiliHelper.nullIfBlank(auth.path("name").asText(null));
        long   pubTs   = auth.path("pub_ts").asLong(0);
        Instant pubDate = pubTs > 0 ? Instant.ofEpochSecond(pubTs) : null;

        // default dynamic link
        String link = idStr != null ? "https://t.bilibili.com/" + idStr : null;

        String title;
        String description;
        List<String> categories = extractTopics(dyn);

        switch (type) {
            case "DYNAMIC_TYPE_AV" -> {
                JsonNode archive = dyn.path("major").path("archive");
                String bvid  = archive.path("bvid").asText(null);
                String cover = archive.path("cover").asText(null);
                title        = archive.path("title").asText("视频动态");
                String desc  = archive.path("desc").asText(null);
                String dynText = dynText(dyn);
                if (bvid != null) link = "https://www.bilibili.com/video/" + bvid;
                description = buildVideoDesc(cover, dynText, desc, link, title);
            }
            case "DYNAMIC_TYPE_DRAW" -> {
                String text = dynText(dyn);
                title       = truncate(text, 60);
                description = buildDrawDesc(text, dyn.path("major").path("draw").path("items"));
            }
            case "DYNAMIC_TYPE_WORD" -> {
                String text = dynText(dyn);
                title       = truncate(text, 60);
                description = text != null ? "<p>" + BilibiliHelper.escapeHtml(text) + "</p>" : null;
            }
            case "DYNAMIC_TYPE_ARTICLE", "DYNAMIC_TYPE_OPUS" -> {
                JsonNode opus = dyn.path("major").path("opus");
                String jumpUrl = opus.path("jump_url").asText(null);
                if (jumpUrl != null && !jumpUrl.startsWith("http")) jumpUrl = "https:" + jumpUrl;
                if (jumpUrl != null) link = jumpUrl;
                title       = opus.path("title").asText(null);
                if (title == null) title = truncate(opus.path("summary").path("text").asText(null), 60);
                description = buildOpusDesc(opus);
            }
            case "DYNAMIC_TYPE_FORWARD" -> {
                String repostText = dynText(dyn);
                title = "转发：" + truncate(repostText, 40);
                description = buildForwardDesc(repostText, item.path("orig"));
            }
            default -> {
                String text = dynText(dyn);
                title       = truncate(text != null ? text : type, 60);
                description = text != null ? "<p>" + BilibiliHelper.escapeHtml(text) + "</p>" : null;
            }
        }

        if (link == null) return null;
        return new FeedItem(title, link, description, pubDate, author, categories);
    }

    // ── description builders ──────────────────────────────────────────────────

    private static String buildVideoDesc(String cover, String dynText, String videoDesc,
                                          String link, String title) {
        var sb = new StringBuilder();
        if (cover != null) sb.append("<img src=\"").append(cover).append("\"><br>");
        if (dynText != null && !dynText.isBlank())
            sb.append("<p>").append(BilibiliHelper.escapeHtml(dynText)).append("</p>");
        if (videoDesc != null && !videoDesc.isBlank())
            sb.append("<p>").append(BilibiliHelper.escapeHtml(videoDesc)).append("</p>");
        if (link != null)
            sb.append("<p>视频：<a href=\"").append(link).append("\">").append(BilibiliHelper.escapeHtml(title)).append("</a></p>");
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String buildDrawDesc(String text, JsonNode items) {
        var sb = new StringBuilder();
        if (text != null && !text.isBlank())
            sb.append("<p>").append(BilibiliHelper.escapeHtml(text)).append("</p>");
        if (items.isArray()) {
            for (JsonNode img : items) {
                String src = img.path("src").asText(null);
                if (src != null) sb.append("<img src=\"").append(src).append("\"><br>");
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String buildOpusDesc(JsonNode opus) {
        var sb = new StringBuilder();
        JsonNode covers = opus.path("pics");
        if (covers.isArray() && !covers.isEmpty()) {
            sb.append("<img src=\"").append(covers.get(0).path("url").asText()).append("\"><br>");
        }
        String summary = opus.path("summary").path("text").asText(null);
        if (summary != null && !summary.isBlank())
            sb.append("<p>").append(BilibiliHelper.escapeHtml(summary)).append("</p>");
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String buildForwardDesc(String repostText, JsonNode orig) {
        var sb = new StringBuilder();
        if (repostText != null && !repostText.isBlank())
            sb.append("<p>").append(BilibiliHelper.escapeHtml(repostText)).append("</p>");
        // Append a summary of the original dynamic
        String origAuthor = orig.path("modules").path("module_author").path("name").asText(null);
        String origText   = dynText(orig.path("modules").path("module_dynamic"));
        if (origAuthor != null || origText != null) {
            sb.append("<blockquote>");
            if (origAuthor != null) sb.append("<b>@").append(BilibiliHelper.escapeHtml(origAuthor)).append("</b>: ");
            if (origText  != null) sb.append(BilibiliHelper.escapeHtml(origText));
            sb.append("</blockquote>");
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String dynText(JsonNode dyn) {
        return BilibiliHelper.nullIfBlank(dyn.path("desc").path("text").asText(null));
    }

    private static List<String> extractTopics(JsonNode dyn) {
        var topics = new ArrayList<String>();
        JsonNode topic = dyn.path("topic");
        if (!topic.isMissingNode() && !topic.isNull()) {
            String name = topic.path("name").asText(null);
            if (name != null) topics.add(name);
        }
        JsonNode nodes = dyn.path("desc").path("rich_text_nodes");
        if (nodes.isArray()) {
            for (JsonNode n : nodes) {
                if ("RICH_TEXT_NODE_TYPE_TOPIC".equals(n.path("type").asText())) {
                    String raw = n.path("text").asText("");
                    // Strip surrounding # symbols
                    String tag = raw.replaceAll("^#+|#+$", "").trim();
                    if (!tag.isBlank()) topics.add(tag);
                }
            }
        }
        return topics.stream().distinct().toList();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "动态";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
