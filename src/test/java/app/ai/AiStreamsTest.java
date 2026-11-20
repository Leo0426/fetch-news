package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AiStreamsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiConfig config = AiConfig.defaults();

    @Test
    void chatKeepsOnlyTheFirstAnswerAndReadsUsageAfterItsFinish() {
        var result = AiStreams.decode("chat", """
                : keep-alive

                data: {"choices":[{"index":1,"delta":{"content":"other"}},
                data: {"index":0,"delta":{"reasoning_content":"hidden","content":"摘"}}]}

                data: {"choices":[{"delta":{"content":"要"},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"completion_tokens":5}}

                data: [DONE]
                """, mapper, config);
        assertEquals("摘要", new OpenAiChatProtocol().text(result));
        assertEquals(5, result.at("/usage/completion_tokens").intValue());
    }

    @Test
    void acceptsExplicitChatTerminationEvenWhenTheGatewayOmitsFinishReason() {
        var result = AiStreams.decode("chat", """
                data: {"choices":[{"delta":{"content":"hello"}}]}

                data: [DONE]
                """, mapper, config);
        assertEquals("hello", new OpenAiChatProtocol().text(result));
    }

    @Test
    void responsesUsesAuthoritativeFinalResponseIncludingStatusAndOutput() {
        var result = AiStreams.decode("responses", """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"draft"}

                event: response.completed
                data: {"type":"response.completed","response":{"status":"completed",
                data: "output":[{"type":"message","content":[{"type":"output_text","text":"complete"}]}]}}
                """, mapper, config);
        assertEquals("complete", result.at("/output/0/content/0/text").textValue());
        assertEquals("completed", result.path("status").textValue());
    }

    @Test
    void anthropicMergesUsageAndExcludesThinkingDeltas() {
        var result = AiStreams.decode("anthropic", """
                data: {"type":"message_start","message":{"usage":{"input_tokens":3}}}

                data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"private"}}

                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}}

                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}

                data: {"type":"message_stop"}
                """, mapper, config);
        assertEquals("hi", new AnthropicMessagesProtocol().text(result));
        assertEquals(3, result.at("/usage/input_tokens").intValue());
        assertEquals(4, result.at("/usage/output_tokens").intValue());
    }

    @Test
    void geminiMergesPartsAndKeepsFinishReason() {
        var result = AiStreams.decode("gemini", """
                data: {"candidates":[{"content":{"parts":[{"text":"hidden","thought":true},{"text":"A"}]}}]}

                data: {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"B"}]}}],
                data: "usageMetadata":{"candidatesTokenCount":5}}
                """, mapper, config);
        assertEquals("AB", result.at("/candidates/0/content/parts/0/text").textValue());
        assertEquals("STOP", result.at("/candidates/0/finishReason").textValue());
        assertEquals(5, result.at("/usageMetadata/candidatesTokenCount").intValue());
    }

    @Test
    void ollamaCollectsNdjsonAndPreservesFinalMetrics() {
        var result = AiStreams.decode("ollama", """
                {"response":"","thinking":"hidden","done":false}
                {"response":"Hi","done":false}
                {"response":"!","done":true,"done_reason":"stop","eval_count":2}
                """, mapper, config);
        assertEquals("Hi!", new OllamaProtocol().text(result));
        assertEquals(2, result.path("eval_count").intValue());
    }

    @Test
    void streamErrorSuppliesProviderReasonAfterAValidHttpResponse() {
        String body = """
                data: {"type":"error","error":{"type":"overloaded_error","message":"sensitive raw message"}}
                """;
        var error = assertThrows(AiProviderFailure.class, () -> AiStreams.decode("anthropic", body, mapper, config));
        assertEquals("模型服务当前过载，请稍后重试。", AiFailureMessages.describe(error));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "chat|data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}",
            "chat|data: {\"choices\":[{\"delta\":{\"tool_calls\":[{}]}}]}",
            "responses|data: {\"type\":\"response.output_text.done\",\"text\":\"partial\"}",
            "anthropic|data: {\"type\":\"message_stop\"}",
            "gemini|data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",
            "ollama|{\"response\":\"partial\",\"done\":false}",
            "ollama|{\"error\":\"private detail\"}"
    })
    void partialAndUnusableStreamsCannotBecomeSummaries(String adapter, String body) {
        assertThrows(IllegalStateException.class, () -> AiStreams.decode(adapter, body, mapper, config));
    }
}
