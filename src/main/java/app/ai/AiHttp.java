package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;

final class AiHttp {
    private AiHttp() {}

    static URI endpoint(AiConfig config, String operation) {
        return URI.create(config.host() + "/" + operation);
    }

    static HttpRequest.Builder builder(AiConfig config, String operation) {
        var builder = HttpRequest.newBuilder(endpoint(config, operation))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json");
        if (config.protocol().equals("anthropic")) builder.header("anthropic-version", "2023-06-01");
        if (!config.apiKey().isBlank()) {
            switch (config.protocol()) {
                case "anthropic" -> builder.header("x-api-key", config.apiKey());
                case "gemini" -> builder.header("x-goog-api-key", config.apiKey());
                default -> builder.header("Authorization", "Bearer " + config.apiKey());
            }
        }
        return builder;
    }

    static HttpRequest post(AiConfig config, String operation, ObjectNode payload, ObjectMapper mapper)
            throws Exception {
        return builder(config, operation)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
    }

    static ObjectNode options(AiConfig config, ObjectMapper mapper) {
        ObjectNode options = mapper.valueToTree(config.parameters());
        options.remove("stream");
        return options;
    }

    static String content(JsonNode content) {
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            var texts = new ArrayList<String>();
            for (var block : content) {
                if (block.path("type").asText().equals("refusal"))
                    throw new IllegalStateException("AI response refused");
                if (block.path("type").asText().equals("text")
                        && block.path("text").isTextual())
                    texts.add(block.path("text").asText());
            }
            return String.join("\n", texts);
        }
        return "";
    }

    static String prompt(String prompt, boolean json) {
        return json
                ? prompt + "\nReturn only a valid JSON object, without Markdown fences or explanatory text."
                : prompt;
    }
}
