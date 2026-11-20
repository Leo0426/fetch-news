package app.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Extracts only wire compatibility evidence from a rejection. The original body stays local. */
final class AiRequestRejection {
    private static final Set<String> UNSUPPORTED = Set.of("unsupported_parameter", "unknown_parameter",
            "unrecognized_request_argument", "extra_forbidden");
    private static final Pattern PARAMETER = Pattern.compile(
            "(?:unsupported (?:parameter|argument)|unknown (?:parameter|field|name)"
                    + "|unrecognized request argument supplied)"
                    + "[: ]+['\"]?([a-zA-Z_][a-zA-Z0-9_.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD_REJECTION = Pattern.compile(
            "(?:^|[\\s'\"])([a-zA-Z_][a-zA-Z0-9_.]*)['\"]?(?::| is| are)?\\s+"
                    + "(?:not supported|unsupported|extra inputs are not permitted)", Pattern.CASE_INSENSITIVE);

    static AiGenerationPlan next(AiConfig config, AiGenerationPlan plan, ObjectNode sent, int status,
            String body, ObjectMapper mapper) {
        if (status != 400 && status != 404 && status != 405 && status != 422) return null;
        JsonNode error = null;
        String message = body;
        try {
            JsonNode node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (node != null) {
                error = node.get("error");
                message = node.path("message").asText(node.path("detail").asText(""));
            }
        } catch (IOException ignored) {
            // A missing endpoint can legitimately return a plain text or HTML 404.
        }
        String code = error == null ? "" : error.path("code").asText("");
        if (error != null) message = error.isTextual() ? error.textValue() : error.path("message").asText("");
        String lower = message.toLowerCase(Locale.ROOT);
        String param = error == null ? "" : error.path("param").asText("");
        if (config.protocol().equals("openai") && config.apiStyle().equals("auto")
                && (param.isEmpty() || param.equals("model") || param.equals("endpoint"))
                && wrongEndpoint(plan.adapter(), status, code, lower)) return plan.switchEndpoint();
        if ((status != 400 && status != 422) || error == null) return null;
        String field = rejectedField(error, code, message, lower);
        if (!plan.stream() && !config.parameters().containsKey("stream")
                && (code.equals("stream_required") || lower.matches(
                ".*\\b(stream must be (set to )?true|only streaming (?:requests|responses) (?:are )?supported)\\b.*")))
            return plan.withStreaming();
        if (plan.adapter().equals("chat") && field.equals(plan.tokenField()) && sent.has(field))
            return plan.switchTokens();
        String format = switch (plan.adapter()) {
            case "chat" -> "response_format";
            case "responses" -> "text.format";
            case "ollama" -> "format";
            case "gemini" -> "generationConfig.responseMimeType";
            default -> "";
        };
        String pointer = "/" + format.replace('.', '/');
        boolean rejectsJson = lower.contains("json mode") && (lower.contains("not support")
                || lower.contains("unsupported"));
        if (!format.isEmpty() && !sent.at(pointer).isMissingNode() && (rejectsJson || field.equals(format)
                || field.startsWith(format + ".") || format.endsWith("." + field))) return plan.withoutNativeJson();
        return null;
    }

    private static boolean wrongEndpoint(String adapter, int status, String code, String message) {
        if (NonTextOutput.rejection(message) != null || message.contains("v1/completions")
                || message.contains("only supports image") || message.contains("only supports audio")
                || message.contains("does not support text")) return false;
        if (Set.of("invalid_api_key", "insufficient_quota", "billing_hard_limit_reached", "context_length_exceeded",
                "rate_limit_exceeded", "permission_denied", "unsupported_modality").contains(code)) return false;
        if (status == 405 || Set.of("unsupported_endpoint", "endpoint_not_supported", "unknown_endpoint")
                .contains(code)) return true;
        String current = adapter.equals("chat") ? "chat/completions" : "responses";
        String other = adapter.equals("chat") ? "responses" : "chat/completions";
        boolean explicitHint = message.contains("only supported") || message.contains("use ")
                || message.contains("only available");
        if (message.contains(other) && explicitHint) return true;
        if (message.contains(current) && (message.contains("not supported in")
                || message.contains("not supported on") || message.contains("not supported with")
                || message.contains("does not support this endpoint"))) return true;
        if (status != 404 || code.equals("model_not_found") || message.contains("model")) return false;
        return code.isEmpty() || code.equals("not_found") || code.equals("invalid_request_error");
    }

    private static String rejectedField(JsonNode error, String code, String message, String lower) {
        String field = error.path("param").asText("");
        if (!field.isEmpty() && (UNSUPPORTED.contains(code) || lower.contains("not supported")
                || lower.contains("unsupported") || lower.contains("only the default"))) return field;
        var matcher = PARAMETER.matcher(message);
        if (matcher.find()) return matcher.group(1);
        matcher = FIELD_REJECTION.matcher(message);
        if (matcher.find()) return matcher.group(1);
        return "";
    }
}
