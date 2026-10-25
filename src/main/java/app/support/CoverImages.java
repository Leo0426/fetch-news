package app.support;

import app.core.Feed;
import app.core.FeedItem;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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
        if (html == null || (!html.contains("<img") && !html.contains("poster="))) return null;
        for (Element img : Jsoup.parseBodyFragment(html).select("img, video[poster]")) {
            if (isPixel(img.attr("width")) || isPixel(img.attr("height"))) continue;
            String src = img.hasAttr("poster") ? img.attr("poster")
                    : img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
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
            String image = item.image() != null ? null : firstInHtml(item.description(), item.link());
            if (image != null) {
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
            URI uri = baseUrl == null || baseUrl.isBlank()
                    ? URI.create(href.strip())
                    : URI.create(baseUrl).resolve(href.strip());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
            String url = uri.toString();
            return url.length() <= MAX_URL_LENGTH ? url : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isPixel(String size) {
        return size.matches("[0-2]");
    }
}
