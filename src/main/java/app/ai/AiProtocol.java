package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpRequest;

public interface AiProtocol {
    String operation(AiConfig config, boolean stream);

    ObjectNode payload(AiConfig config, String prompt, boolean nativeJson, ObjectMapper mapper);

    String text(JsonNode response);

    default HttpRequest request(AiConfig config, String prompt, boolean json, ObjectMapper mapper) throws Exception {
        var plan = AiGenerationPlan.initial(config);
        return plan.request(this, config, plan.body(this, config, prompt, json, mapper), mapper);
    }
}
