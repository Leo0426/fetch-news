package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class OllamaProtocol implements AiProtocol {
    @Override
    public String operation(AiConfig config, boolean stream) {
        return "api/generate";
    }

    @Override
    public ObjectNode payload(AiConfig config, String prompt, boolean nativeJson, ObjectMapper mapper) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());
        body.put("prompt", prompt);
        ObjectNode options = AiHttp.options(config, mapper);
        if (options.has("think")) body.set("think", options.remove("think"));
        options.put("num_predict", config.maxTokens());
        body.set("options", options);
        if (nativeJson) body.put("format", "json");
        return body;
    }

    @Override
    public String text(JsonNode response) {
        String reason = response.path("done_reason").asText("stop");
        if (reason.equals("length")) throw new IllegalStateException("AI output token limit reached");
        if (!response.path("done").asBoolean(true) || !reason.equals("stop"))
            throw new IllegalStateException("AI response did not complete");
        return response.path("response").isTextual() ? response.path("response").asText() : "";
    }
}
