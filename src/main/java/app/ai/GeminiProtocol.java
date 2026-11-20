package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;

final class GeminiProtocol implements AiProtocol {
    static String modelId(String name) {
        return name.startsWith("models/") ? name.substring("models/".length()) : name;
    }

    @Override
    public String operation(AiConfig config, boolean stream) {
        String id = URLEncoder.encode(modelId(config.model()), StandardCharsets.UTF_8).replace("+", "%20");
        return "models/" + id + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent");
    }

    @Override
    public ObjectNode payload(AiConfig config, String prompt, boolean nativeJson, ObjectMapper mapper) {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("contents").addObject().put("role", "user").putArray("parts")
                .addObject().put("text", prompt);
        ObjectNode options = AiHttp.options(config, mapper);
        options.put("maxOutputTokens", config.maxTokens());
        if (nativeJson) options.put("responseMimeType", "application/json");
        body.set("generationConfig", options);
        return body;
    }

    @Override
    public String text(JsonNode response) {
        String blocked = response.path("promptFeedback").path("blockReason").asText("");
        if (!blocked.isEmpty() && !blocked.equals("BLOCK_REASON_UNSPECIFIED"))
            throw new IllegalStateException("AI response refused");
        JsonNode candidate = response.path("candidates").path(0);
        String reason = candidate.path("finishReason").asText();
        if (reason.equals("MAX_TOKENS")) throw new IllegalStateException("AI output token limit reached");
        if (Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY",
                "IMAGE_RECITATION", "IMAGE_PROHIBITED_CONTENT").contains(reason))
            throw new IllegalStateException("AI response refused");
        if (reason.equals("MALFORMED_FUNCTION_CALL") || reason.equals("UNEXPECTED_TOOL_CALL"))
            throw new IllegalStateException("AI response requires unsupported tools");
        if (!reason.equals("STOP")) throw new IllegalStateException("AI response did not complete");
        var texts = new ArrayList<String>();
        for (var part : candidate.path("content").path("parts")) {
            if (part.has("functionCall") || part.has("executableCode"))
                throw new IllegalStateException("AI response requires unsupported tools");
            if (!part.path("thought").asBoolean() && part.path("text").isTextual())
                texts.add(part.get("text").textValue());
        }
        return String.join("", texts);
    }
}
