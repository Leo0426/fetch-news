package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class AnthropicMessagesProtocol implements AiProtocol {
    @Override
    public String operation(AiConfig config, boolean stream) {
        return "messages";
    }

    @Override
    public ObjectNode payload(AiConfig config, String prompt, boolean nativeJson, ObjectMapper mapper) {
        ObjectNode body = AiHttp.options(config, mapper);
        body.put("model", config.model());
        body.put("max_tokens", config.maxTokens());
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        return body;
    }

    @Override
    public String text(JsonNode response) {
        String stop = response.path("stop_reason").asText("");
        if (stop.equals("max_tokens")) throw new IllegalStateException("AI output token limit reached");
        if (stop.equals("refusal")) throw new IllegalStateException("AI response refused");
        if (!stop.isEmpty() && !stop.equals("end_turn") && !stop.equals("stop_sequence"))
            throw new IllegalStateException("AI response did not complete");
        for (var block : response.path("content"))
            if (block.path("type").asText().equals("tool_use"))
                throw new IllegalStateException("AI response requires unsupported tools");
        return AiHttp.content(response.path("content"));
    }
}
