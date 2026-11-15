package app.support;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Resolves source HTML image conventions to plain images shared by covers and article rendering. */
public final class HtmlImages {
    private static final List<String> LAZY_SOURCES = List.of(
            "data-src", "data-original", "data-lazy-src", "data-actualsrc", "data-url", "data-original-src");

    private HtmlImages() {}

    public static String unwrapBody(String html) {
        if (html == null) return "";
        String body = html.strip();
        // Older feed snapshots retained the XML wrapper around HTML bodies.
        return body.startsWith("<![CDATA[") && body.endsWith("]]>")
                ? body.substring(9, body.length() - 3) : body;
    }

    public static void normalize(Element root, String baseUrl) {
        Set<Element> fallbackImages = new HashSet<>();
        for (Element fallback : root.select("noscript")) {
            Element body = Jsoup.parseBodyFragment(fallback.html()).body();
            fallbackImages.addAll(body.select("img"));
            for (Element child : List.copyOf(body.children())) fallback.before(child);
            fallback.remove();
        }
        for (Element picture : root.select("picture")) {
            Element img = picture.selectFirst("img");
            String url = null;
            for (Element source : picture.select("source")) {
                url = sourceUrl(source, baseUrl);
                if (url != null) break;
            }
            if (url != null) {
                if (img == null) img = picture.appendElement("img");
                for (String attribute : LAZY_SOURCES) img.removeAttr(attribute);
                img.removeAttr("srcset").removeAttr("data-srcset").attr("src", url);
            }
            picture.select("source").remove();
        }
        for (Element img : root.select("img")) {
            String url = sourceUrl(img, baseUrl);
            if (url == null || isPixel(img.attr("width")) || isPixel(img.attr("height"))) {
                img.remove();
            } else {
                img.attr("src", url);
            }
        }
        Set<String> seen = new HashSet<>();
        root.select("img").stream().filter(img -> !fallbackImages.contains(img))
                .forEach(img -> seen.add(img.attr("src")));
        for (Element img : root.select("img")) {
            if (fallbackImages.contains(img) && !seen.add(img.attr("src"))) img.remove();
        }
    }

    private static String sourceUrl(Element image, String baseUrl) {
        String url = fromSrcset(image.attr("data-srcset"), baseUrl);
        if (url != null) return url;
        for (String attribute : LAZY_SOURCES) {
            url = CoverImages.normalize(baseUrl, image.attr(attribute));
            if (url != null) return url;
        }
        url = fromSrcset(image.attr("srcset"), baseUrl);
        return url != null ? url : CoverImages.normalize(baseUrl, image.attr("src"));
    }

    private static String fromSrcset(String value, String baseUrl) {
        String best = null;
        double bestSize = -1;
        int cursor = 0;
        while (cursor < value.length()) {
            while (cursor < value.length()
                    && (Character.isWhitespace(value.charAt(cursor)) || value.charAt(cursor) == ',')) cursor++;
            int start = cursor;
            while (cursor < value.length() && !Character.isWhitespace(value.charAt(cursor))) cursor++;
            String candidate = value.substring(start, cursor);
            String descriptor = "";
            if (candidate.endsWith(",")) {
                candidate = candidate.replaceFirst(",+$", "");
            } else {
                start = cursor;
                while (cursor < value.length() && value.charAt(cursor) != ',') cursor++;
                descriptor = value.substring(start, cursor).strip();
            }
            String url = CoverImages.normalize(baseUrl, candidate);
            double size = descriptorSize(descriptor);
            if (url != null && size > bestSize) {
                best = url;
                bestSize = size;
            }
        }
        return best;
    }

    private static double descriptorSize(String descriptor) {
        if (descriptor.isEmpty()) return 1;
        if (!descriptor.matches("[0-9]+(?:\\.[0-9]+)?[wx]")) return -1;
        try {
            double size = Double.parseDouble(descriptor.substring(0, descriptor.length() - 1));
            return size > 0 && Double.isFinite(size) ? size : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean isPixel(String size) {
        return size.strip().matches("(?i)[0-2](?:\\.0+)?(?:px)?");
    }
}
