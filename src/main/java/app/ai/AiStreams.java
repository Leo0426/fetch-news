package app.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;

/** Collects one completed text generation in the same wire shape as its non-streaming response. */
final class AiStreams {
    private final ObjectReader reader;
    private final ObjectNode result;
    private final StringBuilder text = new StringBuilder();
    private final String adapter;
    private final AiConfig config;
    private boolean complete;
    private NonTextOutput media;

    private AiStreams(String adapter, ObjectMapper mapper, AiConfig config) {
        this.adapter = adapter;
        this.config = config;
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        result = mapper.createObjectNode();
    }

    static JsonNode decode(String adapter, String body, ObjectMapper mapper, AiConfig config) {
        AiStreams stream = new AiStreams(adapter, mapper, config);
        Iterator<String> lines = body.lines().iterator();
        StringBuilder event = new StringBuilder();
        while (lines.hasNext()) {
            String line = lines.next();
            if (adapter.equals("ollama")) {
                if (!line.isBlank()) stream.event(line);
            } else if (line.isEmpty()) {
                stream.flush(event);
            } else if (line.startsWith("data:")) {
                if (!event.isEmpty()) event.append('\n');
                event.append(line.substring(line.startsWith("data: ") ? 6 : 5));
            }
        }
        stream.flush(event);
        if (!stream.complete) throw new IllegalStateException("AI stream did not complete");
        return stream.result;
    }

    private void flush(StringBuilder data) {
        if (!data.isEmpty()) {
            event(data.toString());
            data.setLength(0);
        }
    }

    private void event(String data) {
        if ("[DONE]".equals(data)) {
            if (!complete && (adapter.equals("chat") || adapter.equals("openai"))) finishChat("stop");
            return;
        }
        JsonNode node;
        try {
            node = reader.readTree(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AI platform returned invalid JSON");
        }
        if (node == null || !node.isObject())
            throw new IllegalStateException("AI platform returned an error response");
        if (node.hasNonNull("error") || node.path("type").asText().equals("error"))
            throw AiProviderFailure.fromEnvelope(config, node.hasNonNull("error") ? node.get("error") : node);
        switch (adapter) {
            case "chat", "openai" -> chat(node);
            case "responses" -> responses(node);
            case "anthropic" -> anthropic(node);
            case "gemini" -> gemini(node);
            case "ollama" -> ollama(node);
            default -> throw new IllegalArgumentException("Unknown AI stream protocol");
        }
    }

    private void chat(JsonNode node) {
        if (node.hasNonNull("usage")) result.set("usage", node.get("usage"));
        for (JsonNode choice : node.path("choices")) {
            if (choice.path("index").asInt(0) != 0) continue;
            JsonNode delta = choice.path("delta");
            if (!delta.path("refusal").asText("").isEmpty()) throw new IllegalStateException("AI response refused");
            if (delta.hasNonNull("tool_calls") || delta.hasNonNull("function_call"))
                throw new IllegalStateException("AI response requires unsupported tools");
            rememberMedia(delta.hasNonNull("audio") ? NonTextOutput.AUDIO : NonTextOutput.parts(delta.path("content")));
            text.append(AiHttp.content(delta.path("content")));
            if (choice.hasNonNull("finish_reason")) finishChat(choice.get("finish_reason").asText());
        }
    }

    private void rememberMedia(NonTextOutput kind) {
        if (kind != null) media = kind;
    }

    private void finishChat(String reason) {
        if (media != null && text.toString().isBlank() && reason.equals("stop"))
            throw AiProviderFailure.nonText(config, media);
        ObjectNode choice = result.putArray("choices").addObject();
        choice.put("finish_reason", reason);
        choice.putObject("message").put("content", text.toString());
        complete = true;
    }

    private void responses(JsonNode event) {
        switch (event.path("type").asText()) {
            case "response.completed", "response.incomplete", "response.failed" -> {
                if (!(event.get("response") instanceof ObjectNode response))
                    throw new IllegalStateException("AI stream did not complete");
                result.setAll(response);
                complete = true;
            }
        }
    }

    private void anthropic(JsonNode event) {
        switch (event.path("type").asText()) {
            case "message_start" -> {
                if (event.path("message").path("usage") instanceof ObjectNode usage) result.set("usage", usage);
            }
            case "content_block_start" -> {
                JsonNode block = event.path("content_block");
                String type = block.path("type").asText();
                if (type.contains("tool")) throw new IllegalStateException("AI response requires unsupported tools");
                if (type.equals("image")) rememberMedia(NonTextOutput.IMAGE);
                if (type.equals("audio")) rememberMedia(NonTextOutput.AUDIO);
                if (type.equals("text")) text.append(block.path("text").asText(""));
            }
            case "content_block_delta" -> {
                JsonNode delta = event.path("delta");
                if ("text_delta".equals(delta.path("type").asText())) text.append(delta.path("text").asText(""));
            }
            case "message_delta" -> {
                if (event.path("delta").hasNonNull("stop_reason"))
                    result.set("stop_reason", event.path("delta").get("stop_reason"));
                if (event.get("usage") instanceof ObjectNode usage) result.withObjectProperty("usage").setAll(usage);
            }
            case "message_stop" -> {
                if (media != null && text.toString().isBlank()
                        && result.path("stop_reason").asText().equals("end_turn"))
                    throw AiProviderFailure.nonText(config, media);
                result.putArray("content").addObject().put("type", "text").put("text", text.toString());
                complete = result.hasNonNull("stop_reason");
            }
        }
    }

    private void gemini(JsonNode node) {
        if (node.hasNonNull("usageMetadata")) result.set("usageMetadata", node.get("usageMetadata"));
        if (node.path("promptFeedback").hasNonNull("blockReason"))
            throw new IllegalStateException("AI response refused");
        for (JsonNode candidate : node.path("candidates")) {
            if (candidate.path("index").asInt(0) != 0) continue;
            JsonNode parts = candidate.path("content").path("parts");
            rememberMedia(NonTextOutput.parts(parts));
            for (JsonNode part : parts) {
                if (part.hasNonNull("functionCall") || part.hasNonNull("executableCode"))
                    throw new IllegalStateException("AI response requires unsupported tools");
                if (!part.path("thought").asBoolean()) text.append(part.path("text").asText(""));
            }
            if (candidate.hasNonNull("finishReason")) {
                if (media != null && text.toString().isBlank()
                        && candidate.path("finishReason").asText().equals("STOP"))
                    throw AiProviderFailure.nonText(config, media);
                ObjectNode output = result.putArray("candidates").addObject();
                output.set("finishReason", candidate.get("finishReason"));
                output.putObject("content").putArray("parts").addObject().put("text", text.toString());
                complete = true;
            }
        }
    }

    private void ollama(JsonNode node) {
        text.append(node.path("response").asText(""));
        if (node.path("done").asBoolean() && node instanceof ObjectNode last) {
            result.setAll(last);
            result.put("response", text.toString());
            complete = true;
        }
    }
}
