package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class OpenAiChatProtocol implements AiProtocol {
    @Override
    public String operation(AiConfig config, boolean stream) {
        return "chat/completions";
    }

    @Override
    public ObjectNode payload(AiConfig config, String prompt, boolean nativeJson, ObjectMapper mapper) {
        ObjectNode body = AiHttp.options(config, mapper);
        body.put("model", config.model());
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        if (nativeJson) body.putObject("response_format").put("type", "json_object");
        return body;
    }

    @Override
    public String text(JsonNode response) {
        var choice = response.path("choices").path(0);
        var message = choice.path("message");
        String finish = choice.path("finish_reason").asText("");
        if (finish.equals("length")) throw new IllegalStateException("AI output token limit reached");
        if (finish.equals("content_filter") || !message.path("refusal").asText("").isBlank())
            throw new IllegalStateException("AI response refused");
        if (message.path("tool_calls").size() > 0 || message.hasNonNull("function_call")
                || finish.equals("tool_calls") || finish.equals("function_call"))
            throw new IllegalStateException("AI response requires unsupported tools");
        if (!finish.isEmpty() && !finish.equals("stop"))
            throw new IllegalStateException("AI response did not complete");
        return AiHttp.content(message.path("content"));
    }
}
