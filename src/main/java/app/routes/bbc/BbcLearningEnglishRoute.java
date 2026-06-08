package app.routes.bbc;

import app.core.CacheService;
import app.core.Feed;
import app.core.FeedItem;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Route for BBC Learning English (BBC英语学习).
 *
 * <p>Path: /bbc/learningenglish/:channel
 *
 * <p>Fetches the channel listing page and enriches each article with the
 * full richtext content. Detail pages are cached.
 *
 * <p>Supported channels (same as RSSHub):
 * take-away-english, authentic-real-english, media-english, lingohack,
 * english-in-a-minute, phrasal-verbs, todays-phrase, q-and-a,
 * english-at-work, storytellers
 */
public class BbcLearningEnglishRoute implements RouteHandler {

    private static final String ROOT = "https://www.bbc.co.uk";
    private static final Map<String, String> CHANNEL_LABELS = Map.ofEntries(
            Map.entry("take-away-english",    "随身英语"),
            Map.entry("authentic-real-english","地道英语"),
            Map.entry("media-english",        "媒体英语"),
            Map.entry("lingohack",            "英语大破解"),
            Map.entry("english-in-a-minute",  "一分钟英语"),
            Map.entry("phrasal-verbs",        "短语动词"),
            Map.entry("todays-phrase",        "今日短语"),
            Map.entry("q-and-a",              "你问我答"),
            Map.entry("english-at-work",      "白领英语"),
            Map.entry("storytellers",         "亲子英语故事")
    );

    private final FetchClient fetchClient;
    private final CacheService cacheService;

    public BbcLearningEnglishRoute(FetchClient fetchClient, CacheService cacheService) {
        this.fetchClient  = fetchClient;
        this.cacheService = cacheService;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String channel = context.pathParam("channel");
        if (channel == null || channel.isBlank()) channel = "take-away-english";
        if (!CHANNEL_LABELS.containsKey(channel)) {
            throw new RouteException(RouteError.INVALID_PARAMETER,
                    "unknown channel: " + channel + ". Use one of: " + String.join(", ", CHANNEL_LABELS.keySet()));
        }

        String targetUrl = ROOT + "/learningenglish/chinese/features/" + channel;
        String html = fetchClient.get(targetUrl);
        Document doc = Jsoup.parse(html, ROOT);

        var stubs = new ArrayList<ItemStub>();

        // Featured item (data-widget-index=4)
        Element featured = doc.selectFirst("[data-widget-index='4']");
        if (featured != null) {
            String t    = featured.selectFirst("h2") != null ? featured.selectFirst("h2").text() : null;
            Element a   = featured.selectFirst("h2 a");
            String href = a != null ? ROOT + a.attr("href") : null;
            String date = featured.selectFirst(".details h3") != null
                    ? featured.selectFirst(".details h3").text() : null;
            if (t != null && href != null) stubs.add(new ItemStub(t, href, date));
        }

        // List items
        for (Element li : doc.select(".threecol li")) {
            String t    = li.selectFirst("h2") != null ? li.selectFirst("h2").text() : null;
            Element a   = li.selectFirst("h2 a");
            String href = a != null ? ROOT + a.attr("href") : null;
            String date = li.selectFirst(".details h3") != null
                    ? li.selectFirst(".details h3").text() : null;
            if (t != null && href != null) stubs.add(new ItemStub(t, href, date));
            if (stubs.size() >= 11) break; // 1 featured + 10 list
        }

        if (stubs.isEmpty()) throw new RouteException(RouteError.EMPTY_FEED);

        List<FeedItem> items = new ArrayList<>();
        for (ItemStub stub : stubs) {
            items.add(fetchItem(stub, context));
        }

        String label = CHANNEL_LABELS.get(channel);
        return new Feed(
                "BBC英语学习 — " + label,
                targetUrl,
                "BBC Learning English · " + label,
                items);
    }

    private FeedItem fetchItem(ItemStub stub, RouteContext ctx) {
        try {
            String desc = cacheService.getDetailPage(
                    "bbc:le:" + stub.link(), ctx.detailCacheTtl(), () -> {
                        String html = fetchClient.get(stub.link());
                        Document d = Jsoup.parse(html, ROOT);
                        Element richtext = d.selectFirst(".widget-richtext");
                        return richtext != null ? richtext.html() : null;
                    });
            return new FeedItem(
                    stub.title(), stub.link(), desc,
                    app.support.DateParser.parseDateOrNull(stub.date()),
                    null, List.of());
        } catch (Exception e) {
            return new FeedItem(stub.title(), stub.link(), null,
                    app.support.DateParser.parseDateOrNull(stub.date()),
                    null, List.of());
        }
    }

    private record ItemStub(String title, String link, String date) {}
}
