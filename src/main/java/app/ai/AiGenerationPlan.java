package app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpRequest;

/** The wire choices that worked for this configuration, including native JSON and streaming support. */
record AiGenerationPlan(String adapter, String tokenField, boolean stream, boolean nativeJson) {
    static AiGenerationPlan initial(AiConfig config) {
        String adapter = config.protocol().equals("openai")
                ? (config.apiStyle().equals("responses") ? "responses" : "chat") : config.protocol();
        String token = config.tokenLimitParameter().equals("auto")
                ? "max_completion_tokens" : config.tokenLimitParameter();
        boolean stream = Boolean.TRUE.equals(config.parameters().get("stream"));
        return new AiGenerationPlan(adapter, token, stream, config.jsonMode());
    }

    AiProtocol protocol() {
        return switch (adapter) {
            case "chat" -> new OpenAiChatProtocol();
            case "responses" -> new OpenAiResponsesProtocol();
            case "gemini" -> new GeminiProtocol();
            case "anthropic" -> new AnthropicMessagesProtocol();
            case "ollama" -> new OllamaProtocol();
            default -> throw new IllegalStateException("Unknown AI protocol");
        };
    }

    ObjectNode body(AiProtocol protocol, AiConfig config, String prompt, boolean json, ObjectMapper mapper) {
        ObjectNode body = protocol.payload(config, AiHttp.prompt(prompt, json), json && nativeJson, mapper);
        if (adapter.equals("chat")) body.put(tokenField, config.maxTokens());
        if (!adapter.equals("gemini")) body.put("stream", stream);
        return body;
    }

    HttpRequest request(AiProtocol protocol, AiConfig config, ObjectNode body, ObjectMapper mapper) throws Exception {
        return AiHttp.post(config, protocol.operation(config, stream), body, mapper);
    }

    AiGenerationPlan switchEndpoint() {
        return new AiGenerationPlan(adapter.equals("chat") ? "responses" : "chat", tokenField, stream, nativeJson);
    }

    AiGenerationPlan switchTokens() {
        return new AiGenerationPlan(adapter, tokenField.equals("max_tokens") ? "max_completion_tokens" : "max_tokens",
                stream, nativeJson);
    }

    AiGenerationPlan withStreaming() {
        return new AiGenerationPlan(adapter, tokenField, true, nativeJson);
    }

    AiGenerationPlan withoutNativeJson() {
        return new AiGenerationPlan(adapter, tokenField, stream, false);
    }
}
