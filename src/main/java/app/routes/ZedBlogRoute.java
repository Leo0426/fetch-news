package app.routes;

import app.core.CacheService;
import app.core.FetchClient;
import app.core.Feed;
import app.core.FeedItem;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import app.support.Urls;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Route that scrapes the public Zed blog index into a normalized feed.
 */
public class ZedBlogRoute implements RouteHandler {
    private static final String SOURCE_URL = "https://zed.dev/blog";

    private final FetchClient fetchClient;
    private final CacheService cacheService;

    /**
     * Creates the Zed blog route with explicit fetch and cache dependencies.
     *
     * @param fetchClient client used to retrieve the Zed blog page
     * @param cacheService cache used for upstream source content
     */
    public ZedBlogRoute(FetchClient fetchClient, CacheService cacheService) {
        this.fetchClient = fetchClient;
        this.cacheService = cacheService;
    }

    /**
     * Fetches and parses the Zed blog index into a normalized feed.
     *
     * @param context route request context
     * @return normalized Zed blog feed
     * @throws Exception when fetching or parsing fails
     */
    @Override
    public Feed handle(RouteContext context) throws Exception {
        String html = cacheService.getDetailPage(
                "zed:blog:index",
                context.detailCacheTtl(),
                () -> fetchClient.get(SOURCE_URL));
        try {
            Document document = Jsoup.parse(html, SOURCE_URL);
            // Blog cards are <a href="/blog/slug"> links; exclude tag pages and deduplicate by URL
            Set<String> seen = new LinkedHashSet<>();
            List<FeedItem> items = new ArrayList<>();
            for (Element card : document.select("a[href^=/blog/]")) {
                if (card.attr("href").startsWith("/blog/tagged")) continue;
                if (card.selectFirst("h2") == null) continue;
                FeedItem item = toItem(card);
                if (item.title() == null || item.link() == null) continue;
                if (seen.add(item.link())) {
                    items.add(item);
                }
            }
            if (items.isEmpty()) {
                throw new RouteException(RouteError.EMPTY_FEED);
            }
            return new Feed("Zed Blog", SOURCE_URL, "Latest posts from the Zed blog", items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    /**
     * Converts a Zed blog card anchor into a feed item.
     *
     * <p>Current page structure: {@code <a href="/blog/slug"><div>image</div><div><h2>title</h2><p>desc</p>…</div></a>}
     */
    private FeedItem toItem(Element card) {
        Element h2 = card.selectFirst("h2");
        // Description <p> is the sibling immediately following the <h2>
        Element desc = h2 != null ? h2.nextElementSibling() : null;
        if (desc != null && !"p".equals(desc.tagName())) {
            desc = null;
        }
        return new FeedItem(
                h2 == null ? null : h2.text(),
                Urls.resolve(SOURCE_URL, card.attr("href")),
                desc == null ? null : desc.outerHtml(),
                null,
                null,
                List.of());
    }
}
