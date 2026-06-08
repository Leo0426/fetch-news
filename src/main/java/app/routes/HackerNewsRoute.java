package app.routes;

import app.core.Feed;
import app.core.FeedItem;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Route that serves Hacker News feeds by parsing the HN web page directly.
 *
 * <p>Path: /hn/:feed — where :feed is one of top, new, best, ask, show, job.
 *
 * <p>Single HTTP request per feed; pubDate sourced from {@code .age[title]} (ISO format),
 * category from {@code .sitestr}. No Firebase API or per-item fetch needed.
 */
public class HackerNewsRoute implements RouteHandler {
    private static final String ROOT = "https://news.ycombinator.com";
    private static final int LIMIT = 30;

    private static final Map<String, String> PATHS = Map.of(
            "top",  "",
            "new",  "/newest",
            "best", "/best",
            "ask",  "/ask",
            "show", "/show",
            "job",  "/jobs");

    private static final Map<String, String> LABELS = Map.of(
            "top",  "Top Stories",
            "new",  "Newest",
            "best", "Best Stories",
            "ask",  "Ask HN",
            "show", "Show HN",
            "job",  "Jobs");

    private final FetchClient fetchClient;

    public HackerNewsRoute(FetchClient fetchClient) {
        this.fetchClient = fetchClient;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String feed = context.pathParam("feed");
        if (feed == null || feed.isBlank()) feed = "top";
        if (!PATHS.containsKey(feed)) {
            throw new RouteException(RouteError.INVALID_PARAMETER,
                    "unknown feed: " + feed + ". Valid values: " + String.join(", ", PATHS.keySet()));
        }

        String url = ROOT + PATHS.get(feed);
        String html = fetchClient.get(url);

        try {
            Document doc = Jsoup.parse(html);
            List<FeedItem> items = new ArrayList<>();
            for (Element row : doc.select(".athing")) {
                if (items.size() >= LIMIT) break;
                FeedItem item = parseRow(row);
                if (item != null) items.add(item);
            }
            if (items.isEmpty()) throw new RouteException(RouteError.EMPTY_FEED);

            String label = LABELS.get(feed);
            return new Feed("Hacker News — " + label, url, label + " from Hacker News", items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parse failed: " + e.getMessage(), e);
        }
    }

    private FeedItem parseRow(Element thing) {
        String id = thing.attr("id");
        if (id == null || id.isBlank()) return null;

        Element titleLink = thing.selectFirst(".titleline > a");
        if (titleLink == null) return null;

        String title  = titleLink.text();
        String origin = titleLink.attr("href");
        String hnLink = ROOT + "/item?id=" + id;
        if (origin.startsWith("item?")) origin = ROOT + "/" + origin;

        String site = thing.select(".sitestr").text();
        List<String> categories = site.isBlank() ? List.of() : List.of(site);

        // subtext row immediately follows the .athing row
        Element subtext = thing.nextElementSibling();
        String  author   = null;
        Instant pubDate  = null;
        int     score    = 0;
        String  comments = "";

        if (subtext != null) {
            Element hnuser = subtext.selectFirst(".hnuser");
            if (hnuser != null) author = hnuser.text();

            Element age = subtext.selectFirst(".age");
            if (age != null) {
                try { pubDate = LocalDateTime.parse(age.attr("title")).toInstant(ZoneOffset.UTC); }
                catch (DateTimeParseException ignored) {}
            }

            Element scoreEl = subtext.selectFirst(".score");
            if (scoreEl != null) {
                try { score = Integer.parseInt(scoreEl.text().split(" ")[0]); }
                catch (NumberFormatException ignored) {}
            }

            Elements links = subtext.select("a");
            if (!links.isEmpty()) {
                String last = links.last().text();
                if (last.contains("comment")) comments = last.split(" comment")[0];
            }
        }

        String desc = buildDesc(score, author, hnLink, comments);
        String link = (!origin.equals(hnLink)) ? origin : hnLink;
        return new FeedItem(title, link, desc, pubDate, author, categories);
    }

    private String buildDesc(int score, String author, String hnLink, String comments) {
        StringBuilder sb = new StringBuilder("<p>");
        if (score > 0) sb.append(score).append(" points");
        if (author != null) {
            if (score > 0) sb.append(" by ");
            sb.append(esc(author));
        }
        sb.append(" | <a href=\"").append(hnLink).append("\">");
        sb.append(comments.isBlank() ? "discuss" : comments + " comments");
        sb.append("</a></p>");
        return sb.toString();
    }

    private String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
