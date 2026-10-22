package app.reader;

import java.net.URI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

public record ArticleContent(String html, String kind) {
    private static final Safelist ALLOWED = new Safelist().addTags(
            "p", "br", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "li",
            "pre", "code", "strong", "em", "b", "i", "s", "hr", "table", "thead", "tbody",
            "tr", "th", "td", "a").addAttributes("a", "href");

    public static ArticleContent from(String description, String extract, String baseUrl) {
        var source = Jsoup.parseBodyFragment(description == null ? "" : description);
        boolean plainText = source.body().getAllElements().stream().skip(1)
                .noneMatch(element -> element.tag().isKnownTag());
        String plainSource = plainText && !source.body().children().isEmpty()
                ? Parser.unescapeEntities(description, false) : source.body().wholeText();
        source.select("script, style, iframe, form, svg, math, object, embed, video, audio, canvas, "
                + "link, meta, base, template, noscript, img, picture, source, input, button, select, textarea").remove();
        source.select("a[href]").forEach(link -> {
            String destination = safeDestination(link.attr("href"), baseUrl);
            if (destination == null) link.removeAttr("href");
            else link.attr("href", destination);
        });
        var body = new Cleaner(ALLOWED).clean(source);
        body.outputSettings().prettyPrint(false);
        body.select("a[href]").forEach(link -> link.attr("target", "_blank").attr("rel", "noopener noreferrer"));
        String sourceText = plainText ? plainSource : body.text();
        if (textLength(sourceText) < 200 && textLength(extract) >= 200) {
            return new ArticleContent(paragraphs(extract), "extract");
        }
        if (hasText(sourceText)) {
            return new ArticleContent(plainText ? paragraphs(plainSource) : body.body().html(), "source");
        }
        return hasText(extract) ? new ArticleContent(paragraphs(extract), "extract")
                : new ArticleContent("", "empty");
    }

    private static int textLength(String value) {
        if (value == null) return 0;
        String text = value.strip();
        return text.codePointCount(0, text.length());
    }

    private static boolean hasText(String value) {
        return value != null && value.codePoints()
                .anyMatch(character -> !Character.isWhitespace(character) && !Character.isSpaceChar(character));
    }

    private static String paragraphs(String text) {
        var document = Document.createShell("");
        document.outputSettings().prettyPrint(false);
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        for (String paragraph : normalized.split("\\n(?:[\\p{Zs}\\t]*\\n)+")) {
            if (!hasText(paragraph)) continue;
            var element = document.body().appendElement("p");
            String[] lines = paragraph.split("\n", -1);
            for (int index = 0; index < lines.length; index++) {
                if (index > 0) element.appendElement("br");
                element.appendText(lines[index]);
            }
        }
        return document.body().html();
    }

    private static String safeDestination(String value, String baseUrl) {
        try {
            URI destination = URI.create(value.strip());
            if (!destination.isAbsolute() && baseUrl != null) {
                URI base = URI.create(baseUrl);
                if (isWebUrl(base)) destination = base.resolve(destination);
            }
            return isWebUrl(destination) ? destination.toString() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isWebUrl(URI uri) {
        return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
    }

    public boolean isEmpty() {
        return "empty".equals(kind);
    }
}
