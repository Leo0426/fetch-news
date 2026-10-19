package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;

public interface AiProtocol {
    HttpRequest request(AiConfig config,String prompt,boolean json,ObjectMapper mapper) throws Exception;
    String text(JsonNode response);
}
