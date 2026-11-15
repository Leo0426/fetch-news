package app.support;

import app.core.Feed;
import app.core.FeedItem;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * Finds a cover image for a feed item from feed media tags or the item's description HTML.
 */
public final class CoverImages {
    private static final int MAX_URL_LENGTH = 2048;

    private CoverImages() {}

    /**
     * Reads the cover declared by an RSS item or Atom entry: media:content, media:thumbnail,
     * image enclosures, itunes:image.
     */
    public static String fromFeedEntry(Element entry, String baseUrl) {
        for (Element el : entry.select("media|content, media|thumbnail, enclosure, link[rel=enclosure], itunes|image")) {
            String type = el.attr("type");
            String medium = el.attr("medium");
            boolean image = switch (el.tagName()) {
                case "media:thumbnail", "itunes:image" -> true;
                case "media:content" -> "image".equals(medium) || type.startsWith("image/")
                        || (medium.isEmpty() && type.isEmpty());
                default -> type.startsWith("image/");
            };
            if (!image) continue;
            String url = normalize(baseUrl, el.hasAttr("url") ? el.attr("url") : el.attr("href"));
            if (url != null) return url;
        }
        return null;
    }

    /**
     * Returns the first meaningful {@code <img>} or video poster in description HTML, skipping data URIs and tracking pixels.
     */
    public static String firstInHtml(String html, String baseUrl) {
        if (html == null || html.isBlank() || !html.contains("<")) return null;
        Element body = Jsoup.parseBodyFragment(HtmlImages.unwrapBody(html)).body();
        HtmlImages.normalize(body, baseUrl);
        for (Element img : body.select("img, video[poster]")) {
            String src = img.hasAttr("poster") ? img.attr("poster") : img.attr("src");
            String url = normalize(baseUrl, src);
            if (url != null) return url;
        }
        return null;
    }

    /** Fills missing item covers from description images. Returns the same feed when nothing changes. */
    public static Feed withFallbackCovers(Feed feed) {
        boolean changed = false;
        List<FeedItem> items = new ArrayList<>(feed.items().size());
        for (FeedItem item : feed.items()) {
            String image = normalize(item.link(), item.image());
            if (image == null) image = firstInHtml(item.description(), item.link());
            if (!Objects.equals(image, item.image())) {
                items.add(item.withImage(image));
                changed = true;
            } else {
                items.add(item);
            }
        }
        return changed ? new Feed(feed.title(), feed.link(), feed.description(), items) : feed;
    }

    /** Resolves {@code href} against {@code baseUrl} and keeps it only if it is an absolute http(s) URL. */
    public static String normalize(String baseUrl, String href) {
        if (href == null || href.isBlank() || href.startsWith("data:")) return null;
        try {
            URI uri = URI.create(href.strip());
            if (!uri.isAbsolute() && baseUrl != null && !baseUrl.isBlank()) uri = URI.create(baseUrl).resolve(uri);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
            if (uri.getHost() == null || uri.getUserInfo() != null) return null;
            String url = uri.toString();
            return url.length() <= MAX_URL_LENGTH ? url : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
