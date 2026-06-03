package app.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for JSON feed rendering.
 */
class JsonRendererTest {
    /**
     * Verifies that feed and item fields are serialized into the debug JSON shape.
     */
    @Test
    void rendersFeedAsJson() throws Exception {
        Feed feed = new Feed(
                "Example",
                "https://example.com",
                "Debug feed",
                List.of(new FeedItem(
                        "One",
                        "https://example.com/one",
                        "<p>One</p>",
                        Instant.parse("2026-05-22T08:00:00Z"),
                        "author",
                        List.of("demo"))));

        String json = new JsonRenderer(new ObjectMapper()).render(feed);
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals("Example", root.get("title").asText());
        assertEquals("One", root.get("items").get(0).get("title").asText());
        assertEquals("2026-05-22T08:00:00Z", root.get("items").get(0).get("pubDate").asText());
    }
}
