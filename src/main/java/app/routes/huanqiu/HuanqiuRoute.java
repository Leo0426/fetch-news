package app.routes.huanqiu;

import app.core.Feed;
import app.core.FeedItem;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Route that scrapes the 环球网 (Huanqiu) homepage for news links.
 *
 * <p>Path: /huanqiu/news
 */
public class HuanqiuRoute implements RouteHandler {

    private static final String HOME_URL = "https://www.huanqiu.com/";

    private final FetchClient fetchClient;

    public HuanqiuRoute(FetchClient fetchClient) {
        this.fetchClient = fetchClient;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String html;
        try {
            html = fetchClient.get(HOME_URL);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, e.getMessage(), e);
        }

        Document doc = Jsoup.parse(html, HOME_URL);
        // Collect unique links via insertion-ordered set to deduplicate
        SequencedSet<String> seen = new LinkedHashSet<>();
        List<FeedItem> items = new ArrayList<>();

        // Featured banner items
        for (Element a : doc.select("#foucsBoxCC ul.imgCon li div.imgTitle > a")) {
            addItem(a, seen, items);
        }
        // Primary text news lists
        for (Element a : doc.select(".secNewsList p.listp > a")) {
            addItem(a, seen, items);
        }
        // Secondary dl/dt/dd lists
        for (Element a : doc.select(".thrNewsList dt > a, .thrNewsList dd > a")) {
            addItem(a, seen, items);
        }

        if (items.isEmpty()) {
            throw new RouteException(RouteError.EMPTY_FEED);
        }

        return new Feed("环球网", HOME_URL, "环球网最新新闻", items);
    }

    private static void addItem(Element a, SequencedSet<String> seen, List<FeedItem> items) {
        String title = a.text().strip();
        if (title.isBlank()) return;

        String href = a.attr("href");
        // Skip malformed sponsored links (e.g. ///article/...)
        if (href == null || href.isBlank() || href.startsWith("///")) return;
        // Resolve protocol-relative URLs
        if (href.startsWith("//")) href = "https:" + href;

        if (!seen.add(href)) return;

        items.add(new FeedItem(title, href, null, null, null, List.of()));
    }
}
