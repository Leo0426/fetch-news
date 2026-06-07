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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

/**
 * Route for BBC News channels with full article text.
 *
 * <p>Path: /bbc/news/:channel
 *
 * <p>{@code :channel} follows BBC RSS URL conventions: slashes replaced with
 * hyphens (e.g. {@code world-asia} for world/asia, {@code technology}).
 * Also accepts {@code chinese} (简体) and {@code traditionalchinese} (繁體).
 *
 * <p>Fetches the BBC RSS feed, then enriches each article with full content
 * extracted from {@code __NEXT_DATA__} or {@code __INITIAL_DATA__} JSON
 * embedded in the article page. Detail pages are cached.
 */
public class BBCNewsRoute implements RouteHandler {

    private static final List<String> SKIP_PREFIXES = List.of(
            "/news/live/", "/news/videos/", "/sounds/play/", "/news/extra/");
    private static final Pattern INITIAL_DATA_RE = Pattern.compile(
            "window\\.__INITIAL_DATA__\\s*=\\s*(\\S.*?);\\s*$", Pattern.MULTILINE);

    private final FetchClient fetchClient;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;

    public BBCNewsRoute(FetchClient fetchClient, ObjectMapper objectMapper,
                         CacheService cacheService) {
        this.fetchClient  = fetchClient;
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String channel = context.pathParam("channel");
        if (channel == null || channel.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "channel is required");
        }

        String feedUrl, feedTitle, feedLink;
        switch (channel.toLowerCase()) {
            case "chinese" -> {
                feedUrl   = "https://www.bbc.co.uk/zhongwen/simp/index.xml";
                feedTitle = "BBC News 中文网";
                feedLink  = "https://www.bbc.com/zhongwen/simp";
            }
            case "traditionalchinese" -> {
                feedUrl   = "https://www.bbc.co.uk/zhongwen/trad/index.xml";
                feedTitle = "BBC News 中文網";
                feedLink  = "https://www.bbc.com/zhongwen/trad";
            }
            default -> {
                String path = channel.replace('-', '/');
                feedUrl   = "https://feeds.bbci.co.uk/news/" + path + "/rss.xml";
                feedTitle = "BBC News — " + channel;
                feedLink  = "https://www.bbc.co.uk/news/" + path;
            }
        }

        String xml = fetchClient.get(feedUrl);
        Feed base;
        try {
            base = app.support.FeedParser.parse(xml, feedUrl);
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED,
                    "failed to parse BBC feed: " + e.getMessage(), e);
        }
        if (base.items().isEmpty()) throw new RouteException(RouteError.EMPTY_FEED);

        var enriched = new ArrayList<FeedItem>();
        for (FeedItem item : base.items()) {
            enriched.add(enrichItem(item, channel, context));
        }

        return new Feed(
                base.title() != null ? base.title() : feedTitle,
                base.link()  != null ? base.link()  : feedLink,
                base.description(),
                enriched);
    }

    private FeedItem enrichItem(FeedItem item, String channel, RouteContext ctx) {
        String link = item.link();
        if (link == null || shouldSkip(link)) return item;

        // Normalise bbc.com → bbc.co.uk
        String fetchLink0 = link.replace("www.bbc.com", "www.bbc.co.uk");
        // Fix Chinese feed returning trad links for simp channel
        final String fetchLink = "chinese".equalsIgnoreCase(channel)
                ? fetchLink0.replace("/trad/", "/simp/") : fetchLink0;

        try {
            ArticleContent content = cacheService.getDetailPage(
                    "bbc:article:" + link,
                    ctx.detailCacheTtl(),
                    () -> fetchArticle(fetchLink));

            List<String> cats = content.categories().isEmpty()
                    ? item.categories() : content.categories();
            return new FeedItem(item.title(), link,
                    content.description() != null ? content.description() : item.description(),
                    item.pubDate(), item.author(), cats);
        } catch (Exception e) {
            return item;
        }
    }

    private ArticleContent fetchArticle(String url) throws Exception {
        String html = fetchClient.get(url);
        var doc = Jsoup.parse(html, url);

        // ── __INITIAL_DATA__ (bbc.co.uk / sport) ─────────────────────────────
        var initialScript = doc.select("script:containsData(__INITIAL_DATA__)").first();
        if (initialScript != null) {
            return extractFromInitialData(initialScript.data());
        }

        // ── __NEXT_DATA__ (news / zhongwen) ──────────────────────────────────
        var nextScript = doc.getElementById("__NEXT_DATA__");
        if (nextScript != null) {
            return extractFromNextData(nextScript.data(), url);
        }

        return new ArticleContent(null, List.of());
    }

    private ArticleContent extractFromInitialData(String scriptText) {
        try {
            var m = INITIAL_DATA_RE.matcher(scriptText);
            if (!m.find()) return new ArticleContent(null, List.of());
            String raw = m.group(1).trim();
            // Value may be double-encoded ("...") or a plain object
            JsonNode data = raw.startsWith("\"")
                    ? objectMapper.readTree(objectMapper.readValue(raw, String.class))
                    : objectMapper.readTree(raw);

            JsonNode article = null;
            for (JsonNode entry : data.path("data")) {
                if ("article".equals(entry.path("name").asText())) {
                    article = entry.path("data");
                    break;
                }
            }
            if (article == null) return new ArticleContent(null, List.of());

            JsonNode blocks = article.path("content").path("model").path("blocks");
            String desc = blocks.isArray() ? BbcBlockRenderer.render(blocks) : null;
            List<String> cats = new ArrayList<>();
            for (JsonNode t : article.path("topics")) {
                String title = t.path("title").asText(null);
                if (title != null && !title.isBlank()) cats.add(title);
            }
            return new ArticleContent(desc, cats);
        } catch (Exception e) {
            return new ArticleContent(null, List.of());
        }
    }

    private ArticleContent extractFromNextData(String json, String url) {
        try {
            JsonNode nextData  = objectMapper.readTree(json);
            JsonNode pageProps = nextData.path("props").path("pageProps");

            // /zhongwen/articles/...
            if (url.contains("/zhongwen/articles/")) {
                JsonNode pageData = pageProps.path("pageData");
                JsonNode blocks   = pageData.path("content").path("model").path("blocks");
                List<String> cats = new ArrayList<>();
                for (JsonNode t : pageData.path("metadata").path("tags").path("about"))
                    cats.add(t.path("thingLabel").asText());
                for (JsonNode t : pageData.path("metadata").path("topics"))
                    cats.add(t.path("topicName").asText());
                return new ArticleContent(BbcBlockRenderer.render(blocks),
                        cats.stream().filter(s -> !s.isBlank()).distinct().toList());
            }

            // General: props.pageProps.page[pageKey]
            String pageKey = pageProps.path("pageKey").asText(null);
            if (pageKey == null) return new ArticleContent(null, List.of());
            JsonNode articleData = pageProps.path("page").path(pageKey);

            JsonNode contents = articleData.path("contents");
            if (!contents.isArray()) {
                contents = articleData.path("content").path("model").path("blocks");
            }
            String desc = contents.isArray() ? BbcBlockRenderer.render(contents) : null;

            List<String> cats = new ArrayList<>();
            for (JsonNode t : articleData.path("topics"))
                cats.add(t.path("title").asText());
            return new ArticleContent(desc,
                    cats.stream().filter(s -> !s.isBlank()).distinct().toList());
        } catch (Exception e) {
            return new ArticleContent(null, List.of());
        }
    }

    private boolean shouldSkip(String link) {
        try {
            String path = URI.create(link).getPath();
            return SKIP_PREFIXES.stream().anyMatch(path::startsWith);
        } catch (Exception ignored) {
            return false;
        }
    }

    private record ArticleContent(String description, List<String> categories) {}
}
