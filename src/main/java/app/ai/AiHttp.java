package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

final class AiHttp {
    private AiHttp() {}
    static URI endpoint(AiConfig config,String operation) {
        return URI.create(config.host()+"/"+operation);
    }
    static HttpRequest.Builder builder(AiConfig config,String operation) {
        var builder=HttpRequest.newBuilder(endpoint(config,operation)).timeout(Duration.ofSeconds(120)).header("Content-Type","application/json");
        if(config.protocol().equals("anthropic")) builder.header("anthropic-version","2023-06-01");
        if(!config.apiKey().isBlank()) builder.header(config.protocol().equals("anthropic") ? "x-api-key" : "Authorization",
                config.protocol().equals("anthropic") ? config.apiKey() : "Bearer "+config.apiKey());
        return builder;
    }
    static HttpRequest post(AiConfig config,String operation,Map<String,Object> payload,ObjectMapper mapper) throws Exception {
        return builder(config,operation).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
    }
    static String content(JsonNode content) {
        if(content.isTextual()) return content.asText();
        if(content.isArray()) {
            var texts=new java.util.ArrayList<String>();
            for(var block:content) if(block.path("type").asText().equals("text") && block.path("text").isTextual()) texts.add(block.path("text").asText());
            return String.join("\n",texts);
        }
        return "";
    }
    static String prompt(String prompt,boolean json) { return json ? prompt+"\nReturn only a valid JSON object, without Markdown fences or explanatory text." : prompt; }
}
