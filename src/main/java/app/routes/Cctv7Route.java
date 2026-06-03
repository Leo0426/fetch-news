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
 * Route that scrapes the CCTV-7 国防军事 channel page for video/program links.
 *
 * <p>Path: /cctv/7
 */
public class Cctv7Route implements RouteHandler {

    private static final String HOME_URL = "https://tv.cctv.com/cctv7/";
    private static final Pattern DATE_IN_URL = Pattern.compile("/(\\d{4})/(\\d{2})/(\\d{2})/");

    private final FetchClient fetchClient;

    public Cctv7Route(FetchClient fetchClient) {
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

        // Featured banner (swiper) items
        for (Element li : doc.select("div.pindao19777_ind01 li.swiper-slide")) {
            Element a = li.selectFirst("> a[href]");
            Element txtEl = li.selectFirst("div.txt");
            if (a == null || txtEl == null) continue;
            String title = txtEl.text().strip();
            String href = a.absUrl("href");
            if (title.isBlank() || href.isBlank()) continue;
            if (!seen.add(href)) continue;
            items.add(new FeedItem(title, href, null, dateFromUrl(href), null, List.of()));
        }

        // "最热节目" list items
        for (Element li : doc.select("div.pindao19777_ind04 ul.con_list > li")) {
            Element a = li.selectFirst("div.brief > a[href]");
            if (a == null) continue;
            String title = a.text().strip();
            String href = a.absUrl("href");
            if (title.isBlank() || href.isBlank()) continue;
            if (!seen.add(href)) continue;
            String category = li.selectFirst("span.bq") != null
                    ? li.selectFirst("span.bq").text().strip() : null;
            List<String> cats = category != null && !category.isBlank() ? List.of(category) : List.of();
            items.add(new FeedItem(title, href, null, dateFromUrl(href), null, cats));
        }

        if (items.isEmpty()) {
            throw new RouteException(RouteError.EMPTY_FEED);
        }

        return new Feed("CCTV-7 国防军事", HOME_URL, "CCTV-7国防军事频道最新节目", items);
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
