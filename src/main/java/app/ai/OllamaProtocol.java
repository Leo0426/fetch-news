package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;

final class OllamaProtocol implements AiProtocol {
    @Override
    public HttpRequest request(AiConfig config, String prompt, boolean json, ObjectMapper mapper) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.model());
        payload.put("prompt", prompt);
        payload.put("stream", false);
        if (json && config.jsonMode()) payload.put("format", "json");
        return AiHttp.post(config, "api/generate", payload, mapper);
    }

    @Override
    public String text(JsonNode response) {
        return response.path("response").isTextual() ? response.path("response").asText() : "";
    }
}
