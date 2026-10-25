package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

final class AnthropicMessagesProtocol implements AiProtocol {
    @Override
    public HttpRequest request(AiConfig config, String prompt, boolean json, ObjectMapper mapper) throws Exception {
        return AiHttp.post(
                config,
                "messages",
                Map.of(
                        "model",
                        config.model(),
                        "max_tokens",
                        config.maxTokens(),
                        "stream",
                        false,
                        "messages",
                        List.of(Map.of("role", "user", "content", AiHttp.prompt(prompt, json)))),
                mapper);
    }

    @Override
    public String text(JsonNode response) {
        String stop = response.path("stop_reason").asText("");
        if (!stop.isEmpty() && !stop.equals("end_turn") && !stop.equals("stop_sequence"))
            throw new IllegalStateException("Anthropic response refused, truncated, or requires unsupported tools");
        for (var block : response.path("content"))
            if (block.path("type").asText().equals("tool_use"))
                throw new IllegalStateException("Anthropic returned unsupported tool use");
        return AiHttp.content(response.path("content"));
    }
}
