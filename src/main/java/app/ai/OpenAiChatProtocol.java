package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenAiChatProtocol implements AiProtocol {
    @Override public HttpRequest request(AiConfig config,String prompt,boolean json,ObjectMapper mapper) throws Exception {
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("model",config.model());payload.put("messages",List.of(Map.of("role","user","content",AiHttp.prompt(prompt,json))));
        payload.put("stream",false);payload.put(config.tokenLimitParameter(),config.maxTokens());
        if(json && config.jsonMode()) payload.put("response_format",Map.of("type","json_object"));
        return AiHttp.post(config,"chat/completions",payload,mapper);
    }
    @Override public String text(JsonNode response) {
        var choice=response.path("choices").path(0);var message=choice.path("message");
        String finish=choice.path("finish_reason").asText("");
        if((!finish.isEmpty() && !finish.equals("stop")) || !message.path("refusal").asText("").isBlank()
                || message.path("tool_calls").size()>0) throw new IllegalStateException("OpenAI response refused, truncated, or requires unsupported tools");
        return AiHttp.content(message.path("content"));
    }
}
