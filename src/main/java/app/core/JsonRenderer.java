package app.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Renders normalized feeds as simple JSON for debugging and API consumers.
 */
public class JsonRenderer implements FeedRenderer {
    private final ObjectMapper objectMapper;

    /**
     * Creates a JSON renderer using the supplied mapper.
     *
     * @param objectMapper mapper used to build and serialize JSON nodes
     */
    public JsonRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String format() { return "json"; }

    @Override
    public String contentType() { return "application/json"; }

    /**
     * Serializes a normalized feed to JSON.
     *
     * @param feed feed to render
     * @return JSON representation of the feed
     * @throws Exception when serialization fails
     */
    @Override
    public String render(Feed feed) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", feed.title());
        root.put("link", feed.link());
        root.put("description", feed.description());
        ArrayNode items = root.putArray("items");
        for (FeedItem item : feed.items()) {
            ObjectNode node = items.addObject();
            node.put("title", item.title());
            node.put("link", item.link());
            node.put("description", item.description());
            if (item.pubDate() == null) {
                node.putNull("pubDate");
            } else {
                node.put("pubDate", item.pubDate().toString());
            }
            node.put("author", item.author());
            ArrayNode categories = node.putArray("categories");
            for (String category : item.categories()) {
                categories.add(category);
            }
        }
        return objectMapper.writeValueAsString(root);
    }
}
