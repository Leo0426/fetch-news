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
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Route for CCTV news categories via the CCTV JSONP API.
 *
 * <p>Path: /cctv/news/:category
 * Supported categories: news, china, world, society, law, ent, tech, life, edu
 */
public class CctvNewsRoute implements RouteHandler {

    private static final String LIST_URL =
            "https://news.cctv.com/2019/07/gaiban/cmsdatainterface/page/%s_1.jsonp";
    private static final String ART_API =
            "https://api.cntv.cn/Article/getXinwenNextArticleInfo?serviceId=sjnews&id=%s&t=json";
    private static final String PHO_API =
            "https://api.cntv.cn/Article/contentinfo?id=%s&serviceId=sjnews&t=json";
    private static final String VID_API =
            "https://vdn.apps.cntv.cn/api/getHttpVideoInfo.do?pid=%s";

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CCTV_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "news",    "新闻",
            "china",   "国内",
            "world",   "国际",
            "society", "社会",
            "law",     "法治",
            "ent",     "文娱",
            "tech",    "科技",
            "life",    "生活",
            "edu",     "教育");

    private final FetchClient fetchClient;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    public CctvNewsRoute(FetchClient fetchClient, CacheService cacheService, ObjectMapper objectMapper) {
        this.fetchClient = fetchClient;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String category = context.pathParam("category");
        if (category == null || category.isBlank()) category = "news";
        if (!CATEGORY_LABELS.containsKey(category)) {
            throw new RouteException(RouteError.INVALID_PARAMETER,
                    "unknown category: " + category + ". Use: " + String.join(", ", CATEGORY_LABELS.keySet()));
        }

        String jsonp = fetchClient.get(
                String.format(LIST_URL, category),
                Map.of("Referer", "https://news.cctv.com/" + category));

        JsonNode root;
        try {
            root = objectMapper.readTree(stripJsonp(jsonp));
        } catch (Exception e) {
            throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT, "failed to parse CCTV JSONP response");
        }

        JsonNode list = root.path("data").path("list");
        if (!list.isArray() || list.isEmpty()) {
            throw new RouteException(RouteError.EMPTY_FEED);
        }

        List<FeedItem> items = new ArrayList<>();
        for (JsonNode entry : list) {
            FeedItem item = toItem(entry, context);
            if (item != null) items.add(item);
        }
        if (items.isEmpty()) throw new RouteException(RouteError.EMPTY_FEED);

        String label = CATEGORY_LABELS.get(category);
        return new Feed(
                "央视新闻 — " + label,
                "https://news.cctv.com/" + category,
                "央视新闻" + label + "频道",
                items);
    }

    private FeedItem toItem(JsonNode entry, RouteContext ctx) throws Exception {
        String title = entry.path("title").asText(null);
        String link  = entry.path("url").asText(null);
        if (title == null || link == null || link.isBlank()) return null;

        Instant pubDate = parseFocusDate(entry.path("focus_date").asText(null));
        String  image   = entry.path("image").asText(null);
        String  id      = filenameWithoutExt(link);

        if (id != null) {
            try {
                if (id.startsWith("ART"))  return fetchArticle(id, title, link, pubDate, ctx);
                if (id.startsWith("PHO"))  return fetchPhoto(id, title, link, pubDate, ctx);
                if (id.startsWith("VIDE") && image != null)
                                           return fetchVideo(id, image, title, link, pubDate, ctx);
            } catch (Exception ignored) {
                // detail fetch failed — fall through to item without description
            }
        }
        return new FeedItem(title, link, null, pubDate, null, List.of());
    }

    private FeedItem fetchArticle(String id, String title, String link, Instant pubDate, RouteContext ctx)
            throws Exception {
        String json = cacheService.getDetailPage("cctv:art:" + id, ctx.detailCacheTtl(),
                () -> fetchClient.get(String.format(ART_API, id)));
        JsonNode data = objectMapper.readTree(json);
        return new FeedItem(title, link,
                nullIfBlank(data.path("article_content").asText(null)),
                pubDate,
                nullIfBlank(data.path("article_source").asText(null)),
                List.of());
    }

    private FeedItem fetchPhoto(String id, String title, String link, Instant pubDate, RouteContext ctx)
            throws Exception {
        String json = cacheService.getDetailPage("cctv:pho:" + id, ctx.detailCacheTtl(),
                () -> fetchClient.get(String.format(PHO_API, id)));
        JsonNode data   = objectMapper.readTree(json);
        JsonNode photos = data.path("photo_album_list");
        StringBuilder sb = new StringBuilder();
        if (photos.isArray()) {
            for (JsonNode p : photos) {
                String src   = p.path("photo_url").asText(null);
                String name  = p.path("photo_name").asText(null);
                String brief = p.path("photo_brief").asText(null);
                if (src   != null) sb.append("<img src=\"").append(src).append("\"><br>");
                if (name  != null && !name.isBlank())  sb.append("<strong>").append(name).append("</strong><br>");
                if (brief != null && !brief.isBlank()) sb.append(brief).append("<br>");
            }
        }
        return new FeedItem(title, link,
                sb.isEmpty() ? null : sb.toString(),
                pubDate,
                nullIfBlank(data.path("source").asText(null)),
                List.of());
    }

    private FeedItem fetchVideo(String id, String image, String title, String link, Instant pubDate, RouteContext ctx)
            throws Exception {
        String vid = videoId(image);
        if (vid == null) return new FeedItem(title, link, null, pubDate, null, List.of());
        String json = cacheService.getDetailPage("cctv:vid:" + vid, ctx.detailCacheTtl(),
                () -> fetchClient.get(String.format(VID_API, vid)));
        JsonNode data = objectMapper.readTree(json);
        String hlsUrl = data.path("hls_url").asText(null);
        String description = hlsUrl != null
                ? "<video src=\"" + hlsUrl + "\" controls poster=\"" + image + "\" style=\"width:100%\"></video>"
                : null;
        return new FeedItem(title, link, description, pubDate,
                nullIfBlank(data.path("article_source").asText(null)),
                List.of());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String stripJsonp(String jsonp) {
        int start = jsonp.indexOf('(');
        int end   = jsonp.lastIndexOf(')');
        if (start < 0 || end <= start) {
            throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT, "not a valid JSONP response");
        }
        return jsonp.substring(start + 1, end);
    }

    private static Instant parseFocusDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value.trim(), CCTV_DATE).atZone(CST).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Extracts the filename stem (no extension) from a URL path. */
    private static String filenameWithoutExt(String url) {
        try {
            String path = URI.create(url).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Extracts the video PID from an image URL.
     * e.g. ".../VIDE202401151234-1-1.jpg" → "VIDE202401151234"
     */
    private static String videoId(String imageUrl) {
        try {
            String path = URI.create(imageUrl).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            int dot  = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            int dash = base.indexOf('-');
            return dash > 0 ? base.substring(0, dash) : base;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
