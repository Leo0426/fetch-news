package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;

final class OpenAiResponsesProtocol implements AiProtocol {
    @Override
    public String operation(AiConfig config, boolean stream) {
        return "responses";
    }

    @Override
    public ObjectNode payload(AiConfig config, String prompt, boolean nativeJson, ObjectMapper mapper) {
        ObjectNode body = AiHttp.options(config, mapper);
        nest(body, "reasoning_effort", "reasoning", "effort");
        nest(body, "verbosity", "text", "verbosity");
        body.put("model", config.model());
        body.put("input", prompt);
        body.put("max_output_tokens", config.maxTokens());
        body.put("store", false);
        if (nativeJson) body.withObjectProperty("text").putObject("format").put("type", "json_object");
        return body;
    }

    private static void nest(ObjectNode body, String field, String group, String child) {
        JsonNode value = body.remove(field);
        if (value != null) body.withObjectProperty(group).set(child, value);
    }

    @Override
    public String text(JsonNode response) {
        String reason = response.path("incomplete_details").path("reason").asText("");
        if (reason.equals("max_output_tokens")) throw new IllegalStateException("AI output token limit reached");
        if (reason.equals("content_filter")) throw new IllegalStateException("AI response refused");
        if (!response.path("status").asText().equals("completed"))
            throw new IllegalStateException("AI response did not complete");
        var texts = new ArrayList<String>();
        for (var item : response.path("output")) {
            String type = item.path("type").asText();
            if (type.equals("reasoning") || type.equals("image_generation_call")
                    || item.path("phase").asText().equals("commentary")) continue;
            if (!type.equals("message")) throw new IllegalStateException("AI response requires unsupported tools");
            if (!item.path("status").asText("completed").equals("completed"))
                throw new IllegalStateException("AI response did not complete");
            for (var block : item.path("content")) {
                if (block.path("type").asText().equals("refusal"))
                    throw new IllegalStateException("AI response refused");
                if (block.path("type").asText().equals("output_text") && block.path("text").isTextual())
                    texts.add(block.get("text").textValue());
            }
        }
        return String.join("\n", texts);
    }
}
