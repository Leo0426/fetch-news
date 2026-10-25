package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GenerationMetricsTest {
    @Test
    void parsesProviderUsageAndKeepsMissingCountsUnknown() throws Exception {
        var mapper = new ObjectMapper();
        assertEquals(
                20L,
                GenerationMetrics.from(
                                "openai",
                                mapper.readTree("{\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5}}"),
                                10)
                        .inputTokens());
        assertEquals(
                5L,
                GenerationMetrics.from(
                                "anthropic",
                                mapper.readTree("{\"usage\":{\"input_tokens\":20,\"output_tokens\":5}}"),
                                10)
                        .outputTokens());
        assertEquals(
                7L,
                GenerationMetrics.from("ollama", mapper.readTree("{\"prompt_eval_count\":7,\"eval_count\":2}"), 10)
                        .inputTokens());
        assertEquals(
                null,
                GenerationMetrics.from("openai", mapper.readTree("{}"), 10).inputTokens());
    }
}
