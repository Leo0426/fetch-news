package app.ai;

import com.fasterxml.jackson.databind.JsonNode;

public record GenerationMetrics(Long inputTokens, Long outputTokens, long elapsedMillis) {
    public static GenerationMetrics from(String protocol, JsonNode node, long elapsedMillis) {
        JsonNode usage = node.path("usage");
        return switch (protocol) {
            case "openai" ->
                new GenerationMetrics(
                        count(usage.path("prompt_tokens")), count(usage.path("completion_tokens")), elapsedMillis);
            case "anthropic" ->
                new GenerationMetrics(
                        count(usage.path("input_tokens")), count(usage.path("output_tokens")), elapsedMillis);
            default ->
                new GenerationMetrics(
                        count(node.path("prompt_eval_count")), count(node.path("eval_count")), elapsedMillis);
        };
    }

    private static Long count(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0 ? node.longValue() : null;
    }
}
