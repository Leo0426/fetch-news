package app.routes.bbc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Renders BBC article block trees (from __NEXT_DATA__ {@code contents} arrays)
 * to HTML strings.
 *
 * <p>Mirrors the logic in RSSHub's {@code bbc/utils.tsx renderArticleContent}.
 */
final class BbcBlockRenderer {

    private BbcBlockRenderer() {}

    /** Renders a {@code contents} array to an HTML string. */
    static String render(JsonNode blocks) {
        if (blocks == null || !blocks.isArray()) return "";
        var sb = new StringBuilder();
        int i = 0;
        for (JsonNode block : blocks) {
            sb.append(renderBlock(block, i++));
        }
        return sb.toString();
    }

    // ── block-level ───────────────────────────────────────────────────────────

    private static String renderBlock(JsonNode block, int index) {
        String type = block.path("type").asText();
        JsonNode model = block.path("model");
        return switch (type) {
            case "paragraph" ->
                    "<p>" + renderInline(children(block)) + "</p>";
            case "heading", "subheading", "crosshead" ->
                    "<h2>" + escapeHtml(extractText(children(block))) + "</h2>";
            case "image" ->
                    renderImage(model, index);
            case "video" ->
                    renderVideo(model, index);
            case "unorderedList", "list" ->
                    "<ul>" + renderListItems(children(block)) + "</ul>";
            case "orderedList" ->
                    "<ol>" + renderListItems(children(block)) + "</ol>";
            // skip chrome / navigation / ads
            case "headline", "timestamp", "byline", "advertisement",
                 "embed", "disclaimer", "continueReading", "mpu", "wsoj",
                 "relatedContent", "links", "metadata", "topicList",
                 "promoList", "visuallyHiddenHeadline", "fauxHeadline" ->
                    "";
            default ->
                    render(children(block));
        };
    }

    // ── inline rendering ─────────────────────────────────────────────────────

    private static String renderInline(JsonNode blocks) {
        if (blocks == null || !blocks.isArray()) return "";
        var sb = new StringBuilder();
        for (JsonNode block : blocks) {
            sb.append(renderInlineBlock(block));
        }
        return sb.toString();
    }

    private static String renderInlineBlock(JsonNode block) {
        String type = block.path("type").asText();
        JsonNode model = block.path("model");
        return switch (type) {
            case "fragment" -> {
                String text = escapeHtml(model.path("text").asText(""));
                for (JsonNode attr : model.path("attributes")) {
                    text = switch (attr.asText()) {
                        case "bold"   -> "<strong>" + text + "</strong>";
                        case "italic" -> "<em>" + text + "</em>";
                        default       -> text;
                    };
                }
                yield text;
            }
            case "urlLink" -> {
                String href = model.path("locator").asText("#");
                String inner = renderInline(children(block));
                yield "<a href=\"" + href + "\">" + inner + "</a>";
            }
            default -> renderInline(children(block));
        };
    }

    // ── list items ────────────────────────────────────────────────────────────

    private static String renderListItems(JsonNode items) {
        if (items == null || !items.isArray()) return "";
        var sb = new StringBuilder();
        for (JsonNode item : items) {
            sb.append("<li>").append(renderInline(children(item))).append("</li>");
        }
        return sb.toString();
    }

    // ── image ─────────────────────────────────────────────────────────────────

    private static String renderImage(JsonNode model, int index) {
        // Path 1: model.image.src (newer format)
        JsonNode imageNode = model.path("image");
        String src = imageNode.path("src").asText(null);
        String alt = imageNode.path("alt").asText("");
        String copyright = imageNode.path("copyright").asText(null);

        // Path 2: model.locator + model.originCode (older format)
        if (src == null || src.isBlank()) {
            String locator    = model.path("locator").asText(null);
            String originCode = model.path("originCode").asText(null);
            if (locator != null && originCode != null) {
                src = "https://ichef.bbci.co.uk/news/1536/" + originCode + "/" + locator;
            }
            if (alt.isBlank()) alt = extractText(children(model.path("altText")));
            if (copyright == null || copyright.isBlank())
                copyright = model.path("copyrightHolder").asText(null);
        }

        if (src == null || src.isBlank()) return "";

        String caption = extractText(children(model.path("caption")));
        var sb = new StringBuilder("<figure>");
        sb.append("<img src=\"").append(src).append("\" alt=\"").append(escapeHtml(alt)).append("\">");
        if (!caption.isBlank() || (copyright != null && !copyright.isBlank())) {
            sb.append("<figcaption>");
            if (copyright != null && !copyright.isBlank()) sb.append(escapeHtml(copyright)).append(" / ");
            sb.append(escapeHtml(caption));
            sb.append("</figcaption>");
        }
        sb.append("</figure>");
        return sb.toString();
    }

    // ── video ─────────────────────────────────────────────────────────────────

    private static String renderVideo(JsonNode model, int index) {
        JsonNode media = model.path("media");
        if (!media.isArray() || media.isEmpty()) media = model.path("media").path("items");
        String poster = null;
        String title  = null;
        if (media.isArray() && !media.isEmpty()) {
            poster = media.get(0).path("holdingImageUrl").asText(null);
            title  = media.get(0).path("title").asText(null);
        }
        if (poster == null) return "";
        var sb = new StringBuilder("<figure>");
        sb.append("<img src=\"").append(poster).append("\"");
        if (title != null) sb.append(" alt=\"").append(escapeHtml(title)).append("\"");
        sb.append("> <em>(视频)</em></figure>");
        return sb.toString();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Returns children from model.blocks, or blocks, or items — whichever is present. */
    private static JsonNode children(JsonNode node) {
        if (node == null) return null;
        JsonNode model = node.path("model");
        if (model.isObject()) {
            JsonNode mb = model.path("blocks");
            if (mb.isArray()) return mb;
        }
        JsonNode b = node.path("blocks");
        if (b.isArray()) return b;
        JsonNode it = node.path("items");
        if (it.isArray()) return it;
        return null;
    }

    /** Recursively extracts plain text from a block subtree. */
    static String extractText(JsonNode blocks) {
        if (blocks == null || !blocks.isArray()) return "";
        var sb = new StringBuilder();
        for (JsonNode block : blocks) {
            String type = block.path("type").asText();
            if ("fragment".equals(type)) {
                sb.append(block.path("model").path("text").asText(""));
            } else if (block.path("model").path("text").isTextual()) {
                sb.append(block.path("model").path("text").asText(""));
            } else {
                sb.append(extractText(children(block)));
            }
        }
        return sb.toString();
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
