package app.radar;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.jsoup.Jsoup;

public final class RadarJson {
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private RadarJson() {}

    public static JsonNode parse(ObjectMapper mapper, String text) throws IOException {
        try {
            return mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException("Model returned invalid structured JSON");
        }
    }

    public static String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(CANONICAL.writeValueAsBytes(value)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot hash radar input", e);
        }
    }

    public static String text(JsonNode node, String field, int limit) {
        JsonNode value = node.get(field);
        if (value == null
                || !value.isTextual()
                || value.asText().isBlank()
                || value.asText().codePointCount(0, value.asText().length()) > limit)
            throw new IllegalArgumentException("Invalid model field: " + field);
        return value.asText().strip();
    }

    public static String truncate(String text, int codePoints) {
        return text.codePointCount(0, text.length()) <= codePoints
                ? text
                : text.substring(0, text.offsetByCodePoints(0, codePoints));
    }

    public static String article(String text) {
        return Jsoup.parse(text == null ? "" : text).text().strip();
    }
}
