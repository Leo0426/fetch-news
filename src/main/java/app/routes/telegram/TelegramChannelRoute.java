package app.routes.telegram;

import app.core.Feed;
import app.core.FeedItem;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import app.support.DateParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

public class TelegramChannelRoute implements RouteHandler {
    private static final Pattern CHANNEL = Pattern.compile("[A-Za-z][A-Za-z0-9_]{3,31}");
    private final FetchClient client;
    private final String fixedChannel;

    public TelegramChannelRoute(FetchClient client) {
        this(client, null);
    }

    protected TelegramChannelRoute(FetchClient client, String fixedChannel) {
        this.client = client;
        this.fixedChannel = fixedChannel;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String channel = fixedChannel != null ? fixedChannel : context.pathParam("channel");
        if (channel == null || !CHANNEL.matcher(channel).matches()) {
            throw new RouteException(RouteError.INVALID_PARAMETER,
                    "channel must be a public Telegram username, without @ or a URL");
        }
        String link = "https://t.me/" + channel;
        String previewUrl = "https://t.me/s/" + channel;
        Document document = Jsoup.parse(fetch(previewUrl), previewUrl);
        if (document.selectFirst(".tgme_channel_history") == null) {
            throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT,
                    "Telegram channel @" + channel + " has no public web preview; check its username and visibility");
        }
        String title = document.select(".tgme_channel_info_header_title").text();
        if (title.isBlank()) title = "@" + channel;
        var items = new LinkedHashMap<String, FeedItem>();
        for (Element message : document.select(".tgme_widget_message[data-post]")) {
            String post = message.attr("data-post");
            if (!post.matches("(?i)" + Pattern.quote(channel) + "/[0-9]+")) continue;
            FeedItem item = toItem(message, "https://t.me/" + post, title);
            if (item != null) items.putIfAbsent(item.link(), item);
        }
        if (items.isEmpty()) {
            throw new RouteException(RouteError.EMPTY_FEED,
                    "Telegram channel @" + channel + " has no readable text or link previews on its latest page");
        }
        return new Feed(title, link, document.select(".tgme_channel_info_description").text(),
                new ArrayList<>(items.values()).reversed());
    }

    private String fetch(String url) throws Exception {
        try {
            return client.get(url);
        } catch (RouteException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED,
                    "Failed to fetch Telegram public preview: " + url, e);
        }
    }

    private FeedItem toItem(Element message, String link, String channelTitle) {
        Element text = message.selectFirst(".tgme_widget_message_text");
        Element body = text == null ? new Element("div") : text.clone();
        List<String> categories = body.select("a").stream().filter(this::isHashtag)
                .map(Element::text).map(tag -> tag.substring(1)).distinct().toList();
        body.select("a").stream().filter(this::isHashtag).toList().forEach(Element::remove);
        body.select("script, style").remove();
        String content = body.wholeText().strip();
        String previewTitle = message.select(".link_preview_title").text();
        Element previewDescription = message.selectFirst(".link_preview_description");
        if (content.isBlank() && previewTitle.isBlank()) return null;

        boolean linkOnly = content.isBlank() || content.matches("https?://\\S+");
        String title = linkOnly && !previewTitle.isBlank() ? previewTitle
                : content.lines().filter(line -> !line.isBlank()).findFirst().orElse(previewTitle);
        Document contentDocument = Document.createShell(message.baseUri());
        contentDocument.body().appendChild(body);
        if (linkOnly && previewDescription != null) {
            contentDocument.body().appendElement("p").appendChild(previewDescription.clone());
        }
        String description = new Cleaner(Safelist.basic()).clean(contentDocument).body().html();
        String author = message.select(".tgme_widget_message_from_author").text();
        return new FeedItem(title, link, description,
                DateParser.parseInstantOrNull(message.select(".tgme_widget_message_date time").attr("datetime")),
                author.isBlank() ? channelTitle : author, categories);
    }

    private boolean isHashtag(Element element) {
        return element.text().matches("#[\\p{L}\\p{N}_]+");
    }
}
