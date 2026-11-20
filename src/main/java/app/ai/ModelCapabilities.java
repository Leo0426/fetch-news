package app.ai;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Generation controls for one model. Authentication, inputs and tool calls belong to the protocol. */
public record ModelCapabilities(
        int maxTokens, boolean jsonMode, String tokenLimitParameter, String apiStyle, Map<String, Object> parameters) {
    private static final Set<String> OPENAI = Set.of(
            "temperature", "top_p", "frequency_penalty", "presence_penalty", "seed", "stop", "logit_bias",
            "logprobs", "top_logprobs", "reasoning_effort", "reasoning", "verbosity", "text", "min_p", "top_k",
            "repetition_penalty", "thinking", "enable_thinking", "chat_template_kwargs");
    private static final Set<String> ANTHROPIC = Set.of(
            "temperature", "top_p", "top_k", "stop_sequences", "thinking", "output_config");
    private static final Set<String> GEMINI = Set.of(
            "temperature", "topP", "topK", "seed", "presencePenalty", "frequencyPenalty", "stopSequences",
            "responseLogprobs", "logprobs", "thinkingConfig");
    private static final Set<String> OLLAMA = Set.of(
            "temperature", "top_k", "top_p", "min_p", "typical_p", "seed", "stop", "num_keep", "num_ctx",
            "repeat_last_n", "repeat_penalty", "presence_penalty", "frequency_penalty",
            "mirostat", "mirostat_tau", "mirostat_eta", "penalize_newline", "think");
    private static final Map<String, Set<String>> OPTIONS = Map.of(
            "openai", OPENAI, "anthropic", ANTHROPIC, "gemini", GEMINI, "ollama", OLLAMA);
    private static final Set<String> BOOLEAN_FIELDS = Set.of(
            "stream", "responseLogprobs", "penalize_newline", "includeThoughts", "enable_thinking",
            "enabled", "exclude");
    private static final Set<String> ENUM_FIELDS = Set.of(
            "reasoning_effort", "effort", "verbosity", "think", "type", "display", "thinkingLevel");
    private static final Map<String, Set<String>> NESTED_FIELDS = Map.of(
            "reasoning", Set.of("effort", "max_tokens", "enabled", "exclude"),
            "text", Set.of("verbosity"),
            "thinking", Set.of("type", "budget_tokens", "display"),
            "output_config", Set.of("effort"),
            "thinkingConfig", Set.of("thinkingBudget", "thinkingLevel", "includeThoughts"),
            "chat_template_kwargs", Set.of("enable_thinking"));
    private static final Pattern FIELD = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");
    private static final Pattern ENUM = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{0,39}");
    private static final Pattern EXTENSION = Pattern.compile(
            "(?:^|_)(?:sampler|sampling|reasoning|think|thinking|temperature|penalty|logits|mirostat|softmax)(?:_|$)");
    private static final Pattern PROTECTED = Pattern.compile(
            "(?:key|secret|token(?!s)|credential|auth|bearer|passw|header|tool|message|prompt|input|url|uri|host|http"
                    + "|proxy|endpoint|webhook|callback|storage|store|persist|cache|billing|budget_id|project|account"
                    + "|payment|service_tier|file|media|code|search|exec|plugin|agent|model|output_path|websocket"
                    + "|network|request|route|bucket|telemetry|trace|export|destination|upload|download|dispatch)");

    public ModelCapabilities {
        maxTokens = maxTokens == 0 ? 4096 : maxTokens;
        if (maxTokens < 1 || maxTokens > 1_000_000) {
            throw new InvalidOption("最大输出 Token 必须在 1–1000000 之间");
        }
        tokenLimitParameter = tokenLimitParameter == null ? "auto" : tokenLimitParameter;
        if (!Set.of("auto", "max_tokens", "max_completion_tokens").contains(tokenLimitParameter)) {
            throw new InvalidOption("不支持的输出上限字段");
        }
        apiStyle = apiStyle == null ? "auto" : apiStyle;
        if (!Set.of("auto", "chat", "responses").contains(apiStyle)) {
            throw new InvalidOption("不支持的生成接口");
        }
        parameters = parameters == null ? Map.of() : freeze(parameters);
        checkAlias(parameters, "reasoning_effort", "reasoning", "effort");
        checkAlias(parameters, "verbosity", "text", "verbosity");
    }

    public static ModelCapabilities defaults(String protocol) {
        return new ModelCapabilities(4096, "ollama".equals(protocol), "auto", "auto", Map.of());
    }

    void validateProtocol(String protocol) {
        if (!protocol.equals("openai") && !apiStyle.equals("auto")) {
            throw new InvalidOption("只有 OpenAI 兼容协议支持选择生成接口");
        }
        for (var entry : parameters.entrySet()) {
            String key = entry.getKey();
            if (!key.equals("stream") && !OPTIONS.get(protocol).contains(key)
                    && (knownField(key) || !extensionField(key))) {
                throw new InvalidOption("当前协议不支持模型参数 " + key + "，请调整协议或移除此参数。");
            }
            if (key.equals("logprobs") && protocol.equals("openai") != (entry.getValue() instanceof Boolean)) {
                throw invalid("logprobs");
            }
        }
    }

    private static void checkAlias(Map<String, Object> values, String flat, String group, String leaf) {
        if (values.containsKey(flat) && values.get(group) instanceof Map<?, ?> nested && nested.containsKey(leaf)
                && !values.get(flat).equals(nested.get(leaf))) {
            throw new InvalidOption(flat + " 与 " + group + "." + leaf + " 的设置冲突，请只保留一处或设置相同的值。");
        }
    }

    private static boolean knownField(String field) {
        return OPTIONS.values().stream().anyMatch(fields -> fields.contains(field));
    }

    // Accept future provider-specific generation knobs, without opening arbitrary request-body passthrough.
    private static boolean extensionField(String field) {
        if (!FIELD.matcher(field).matches()) return false;
        String words = field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        return EXTENSION.matcher(words).find() && !PROTECTED.matcher(words).find();
    }

    private static Map<String, Object> freeze(Map<String, Object> options) {
        if (options.size() > 64) throw new InvalidOption("模型参数过多");
        var result = new LinkedHashMap<String, Object>();
        for (var entry : options.entrySet()) {
            String key = entry.getKey();
            if (key == null || (!key.equals("stream") && !knownField(key) && !extensionField(key))) {
                throw new InvalidOption("模型参数包含不支持的字段");
            }
            Object value = entry.getValue();
            if (key.equals("logit_bias")) result.put(key, logitBias(value));
            else if (NESTED_FIELDS.containsKey(key)) result.put(key, nested(key, value));
            else result.put(key, scalar(key, value, !key.equals("stream") && !knownField(key)));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> nested(String field, Object value) {
        if (!(value instanceof Map<?, ?> map) || map.size() > 32) throw invalid(field);
        var result = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid(field);
            boolean extension = !NESTED_FIELDS.get(field).contains(key);
            if (extension && !extensionField(key)) throw invalid(field);
            result.put(key, scalar(key, entry.getValue(), extension));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Number> logitBias(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.size() > 1024) throw invalid("logit_bias");
        var result = new LinkedHashMap<String, Number>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !key.matches("[0-9]{1,10}")
                    || !(entry.getValue() instanceof Number bias)
                    || !Double.isFinite(bias.doubleValue()) || Math.abs(bias.doubleValue()) > 100) {
                throw invalid("logit_bias");
            }
            result.put(key, number("logit_bias", bias));
        }
        return Map.copyOf(result);
    }

    private static Object scalar(String key, Object value, boolean extension) {
        if (Set.of("stop", "stop_sequences", "stopSequences").contains(key)) {
            if (key.equals("stop") && stopSequence(value)) return value;
            if (value instanceof List<?> list && !list.isEmpty() && list.size() <= 64
                    && list.stream().allMatch(ModelCapabilities::stopSequence)) {
                return List.copyOf(list);
            }
            throw invalid(key);
        }
        if (value instanceof Boolean && (key.equals("think") || key.equals("logprobs") || extension)) return value;
        if (BOOLEAN_FIELDS.contains(key)) {
            if (value instanceof Boolean) return value;
            throw invalid(key);
        }
        if (extension || ENUM_FIELDS.contains(key)) {
            if (value instanceof String text && ENUM.matcher(text).matches()) return text;
            if (!extension) throw invalid(key);
        }
        if (value instanceof Number number) return number(key, number);
        throw invalid(key);
    }

    // Use the same immutable numeric types Jackson produces on reload, including small long values.
    private static Number number(String key, Number value) {
        if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            if (Double.isFinite(value.doubleValue())) return value.doubleValue();
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger) {
            if (value instanceof BigInteger integer && integer.bitLength() > 63) {
                if (integer.bitLength() <= 128) return integer;
            } else {
                long integral = value.longValue();
                if (integral >= Integer.MIN_VALUE && integral <= Integer.MAX_VALUE) return (int) integral;
                return integral;
            }
        }
        throw invalid(key);
    }

    private static boolean stopSequence(Object value) {
        return value instanceof String text && !text.isEmpty() && text.length() <= 1024;
    }

    private static InvalidOption invalid(String field) {
        String label = knownField(field) || BOOLEAN_FIELDS.contains(field) || ENUM_FIELDS.contains(field)
                || Set.of("budget_tokens", "max_tokens", "thinkingBudget").contains(field) ? field : "扩展参数";
        String hint;
        if (BOOLEAN_FIELDS.contains(field)) hint = "请使用 true 或 false。";
        else if (ENUM_FIELDS.contains(field)) hint = "请填写该模型支持的简短选项值。";
        else if (field.equals("logit_bias")) hint = "请使用包含数字 Token ID 与 -100 至 100 权重的对象。";
        else if (NESTED_FIELDS.containsKey(field)) hint = "请使用仅含该接口支持的生成设置的 JSON 对象。";
        else if (Set.of("stop", "stop_sequences", "stopSequences").contains(field)) hint = "请检查停止词的格式和长度。";
        else if (knownField(field)) hint = "请检查此参数的值及类型。";
        else hint = "请使用布尔值、有限数值或简短选项值。";
        return new InvalidOption("模型参数 " + label + " 格式无效，" + hint);
    }

    /** Safe administrative feedback with no submitted values or unrecognized field names. */
    public static final class InvalidOption extends IllegalArgumentException {
        private InvalidOption(String message) {
            super(message);
        }
    }
}
