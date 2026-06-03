package app.routes;

import app.core.Feed;
import app.core.FeedItem;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Route that scrapes the 环球网科技 (tech.huanqiu.com) channel pages.
 *
 * <p>The pages use server-embedded {@code <textarea class="item-*">} elements to carry
 * article data: {@code item-aid} (article ID), {@code item-title}, {@code item-cnf-host},
 * and {@code item-time} (UNIX timestamp in milliseconds). Article URLs are assembled as
 * {@code https://{host}/article/{aid}}.
 *
 * <p>Registered at /huanqiu/tech/:section — :section maps to a sub-path of
 * tech.huanqiu.com (e.g. "original" for the AI column).
 */
public class HuanqiuTechRoute implements RouteHandler {

    private static final String BASE_URL = "https://tech.huanqiu.com/";

    private final FetchClient fetchClient;

    public HuanqiuTechRoute(FetchClient fetchClient) {
        this.fetchClient = fetchClient;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String section = context.pathParam("section");
        if (section == null || section.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "section is required");
        }
        String pageUrl = BASE_URL + section;
        String html;
        try {
            html = fetchClient.get(pageUrl);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, e.getMessage(), e);
        }

        Document doc = Jsoup.parse(html, pageUrl);
        SequencedSet<String> seen = new LinkedHashSet<>();
        List<FeedItem> items = new ArrayList<>();

        for (Element item : doc.select(".data-container .item")) {
            String aid   = textOf(item, "item-aid");
            String title = textOf(item, "item-title");
            String host  = textOf(item, "item-cnf-host");
            String tsRaw = textOf(item, "item-time");

            if (aid == null || title == null || title.isBlank()) continue;
            if (host == null || host.isBlank()) host = "tech.huanqiu.com";

            String link = "https://" + host + "/article/" + aid;
            if (!seen.add(link)) continue;

            Instant pubDate = null;
            if (tsRaw != null && !tsRaw.isBlank()) {
                try {
                    long ms = Long.parseLong(tsRaw.trim());
                    if (ms > 0) pubDate = Instant.ofEpochMilli(ms);
                } catch (NumberFormatException ignored) {}
            }

            items.add(new FeedItem(title, link, null, pubDate, null, List.of()));
        }

        if (items.isEmpty()) {
            throw new RouteException(RouteError.EMPTY_FEED,
                    "no articles found at " + pageUrl + " — page structure may have changed");
        }

        return new Feed("环球网科技·" + section, pageUrl, "环球网科技 " + section + " 栏目", items);
    }

    private static String textOf(Element parent, String cls) {
        Element el = parent.selectFirst("textarea." + cls);
        if (el == null) return null;
        String text = el.text().strip();
        return text.isBlank() ? null : text;
    }
}
