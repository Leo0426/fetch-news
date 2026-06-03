package app.routes;

import app.core.Feed;
import app.core.FeedItem;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Route that scrapes the 新华网 (Xinhua / news.cn) homepage for the latest news.
 *
 * <p>Path: /xinhua/news
 */
public class XinhuaRoute implements RouteHandler {

    private static final String HOME_URL = "https://www.news.cn/";
    // Matches YYYYMMDD in URL paths like .../20260604/abc.../c.html
    private static final Pattern DATE_IN_URL = Pattern.compile("/(\\d{4})(\\d{2})(\\d{2})/");

    private final FetchClient fetchClient;

    public XinhuaRoute(FetchClient fetchClient) {
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
        SequencedSet<String> seen = new LinkedHashSet<>();
        List<FeedItem> items = new ArrayList<>();

        // "最新播报" breaking news ticker
        for (Element a : doc.select("#latest div.latest-cont ul li > a")) {
            addItem(a, seen, items);
        }
        // "要闻聚焦" main news section
        for (Element a : doc.select("#depth div.list ul li > a")) {
            addItem(a, seen, items);
        }
        // Additional list sections on the page
        for (Element a : doc.select("div.list.list-txt ul li > a")) {
            addItem(a, seen, items);
        }

        if (items.isEmpty()) {
            throw new RouteException(RouteError.EMPTY_FEED);
        }

        return new Feed("新华网", HOME_URL, "新华网最新新闻", items);
    }

    private static void addItem(Element a, SequencedSet<String> seen, List<FeedItem> items) {
        String title = a.text().strip();
        if (title.isBlank()) return;
        // absUrl resolves relative hrefs using the base URL set during parse
        String href = a.absUrl("href");
        if (href.isBlank()) return;
        if (!seen.add(href)) return;
        items.add(new FeedItem(title, href, null, dateFromUrl(href), null, List.of()));
    }

    private static Instant dateFromUrl(String url) {
        Matcher m = DATE_IN_URL.matcher(url);
        if (!m.find()) return null;
        try {
            int year  = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day   = Integer.parseInt(m.group(3));
            return LocalDate.of(year, month, day)
                    .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                    .toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
