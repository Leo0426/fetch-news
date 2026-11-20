package app.ai;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.regex.Pattern;

/** UI messages are selected from local failure categories. Provider bodies may contain credentials or prompts. */
public final class AiFailureMessages {
    private static final Pattern HTTP = Pattern.compile(
            "(?:openai|chat|responses|anthropic|ollama|gemini) returned (?:HTTP ([1-5][0-9]{2})|an error)"
                    + "(?: \\[(model|text|embedding|image|audio|video|ranking|endpoint|context|"
                    + "quota|rate|auth|permission|overloaded|parameter ([a-zA-Z0-9_.]{1,100}))\\])?");

    private static final Pattern ARTICLE_HTTP = Pattern.compile("upstream returned HTTP ([1-5][0-9]{2})");

    private AiFailureMessages() {}

    public static String describe(Exception error) {
        String known = forDetail(error.getMessage());
        if (known != null) return known;
        if (error instanceof InterruptedException) return "生成验证已中断，可以重新验证。";
        if (error instanceof HttpTimeoutException) return "模型响应超时，请检查平台连接或稍后重试。";
        if (error instanceof IOException) return "无法连接模型服务，请检查 API 地址、网络和证书。";
        return "模型未通过生成验证，请检查模型 ID、接口和生成参数后重试。";
    }

    public static String summary(String detail, String model, boolean reader) {
        String message = forDetail(detail, reader);
        if (message == null || model == null || model.isBlank()) return message;
        return (HTTP.matcher(detail).matches() || detail.startsWith("AI "))
                ? "使用模型「" + model + "」生成摘要失败。" + message : message;
    }

    public static String forDetail(String detail) {
        return forDetail(detail, false);
    }

    private static String forDetail(String detail, boolean reader) {
        if (detail == null) return null;
        var article = ARTICLE_HTTP.matcher(detail);
        if (article.matches()) return "原文网站返回 HTTP " + article.group(1) + "，无法读取正文。请确认文章是否仍可访问。";
        if (detail.startsWith("upstream request failed"))
            return "获取文章正文时连接原文网站失败，请稍后重试。";
        var http = HTTP.matcher(detail);
        if (http.matches()) {
            String status = http.group(1);
            String reason = http.group(2);
            String configure = reader ? "请联系管理员" : "请";
            String textModel = reader ? "请联系管理员选择能生成文字的模型后重试。"
                    : "请选择能生成文字的模型，再点击“测试模型生成”验证。";
            if (reason != null) {
                String tip = switch (reason) {
                    case "auth" -> "平台未接受 API 密钥，" + configure + "在 AI 设置中更新密钥。";
                    case "permission" -> "当前密钥没有使用模型的权限，" + configure + "检查模型授权。";
                    case "overloaded" -> "模型服务当前过载，请稍后重试。";
                    case "model" -> "平台未找到此模型或当前账号无权使用，" + configure + "核对模型 ID 和授权。";
                    case "text" -> "这个模型不支持当前的文本生成接口。" + textModel;
                    case "embedding" -> "当前接口提供向量嵌入，任务需要文字输出。" + textModel;
                    case "image" -> "当前接口提供图像输出，任务需要文字输出。" + textModel;
                    case "audio" -> "当前接口提供音频输出，任务需要文字输出。" + textModel;
                    case "video" -> "当前接口提供视频输出，任务需要文字输出。" + textModel;
                    case "ranking" -> "当前接口提供相关性分数，任务需要文字输出。" + textModel;
                    case "endpoint" -> "平台不支持当前生成接口，" + configure + "核对 API 地址和模型接口。";
                    case "context" -> "输入与输出额度超出模型的上下文窗口，请降低输出上限或使用更大上下文的模型。";
                    case "quota" -> "平台额度已用尽，请检查账户余额、预算和配额。";
                    case "rate" -> "平台限制了请求频率或额度，请检查配额后稍后重试。";
                    default -> "平台拒绝了参数 " + http.group(3) + "，" + configure + "在该模型的高级设置中调整或移除它。";
                };
                return status == null ? tip : "HTTP " + status + "，" + tip;
            }
            if (status == null) return "模型服务返回了错误，请在 AI 设置中测试该模型的生成能力。";
            String remedy = switch (status) {
                case "400", "422" -> "平台拒绝了生成请求，" + configure + "核对模型接口和高级参数。";
                case "401" -> "平台未接受 API 密钥，" + configure + "在 AI 设置中更新密钥。";
                case "403" -> "当前密钥没有使用此模型的权限，" + configure + "检查模型授权。";
                case "404", "405" -> configure + "确认 API 地址、模型 ID，以及模型支持的生成接口。";
                case "429", "402" -> "请检查平台额度和请求频率，稍后重试。";
                default -> status.startsWith("5") ? "模型服务暂时不可用，请稍后重试。" : "请检查模型服务和 API 设置。";
            };
            return "HTTP " + status + "，" + remedy;
        }
        return switch (detail) {
            case "AI output token limit reached" -> "模型输出额度已用尽，请在 AI 设置中提高最大输出 Token 或切换模型后重试。";
            case "AI response refused" -> "模型拒绝了本次请求，可以换一篇文章或切换模型。";
            case "AI response requires unsupported tools" -> "模型要求调用工具，请选择可直接返回文本的模型或调整生成参数。";
            case "AI platform returned no text" -> "模型没有返回可用正文，请确认模型支持文本生成，并检查推理与输出额度。";
            case "AI platform returned unexpected verification content" ->
                    "模型已返回文本，但未按验证要求输出 JSON 对象 {\"ok\":true}。请检查模型的指令跟随能力和 JSON 设置。";
            case "Model returned invalid structured JSON" ->
                    "模型未按要求返回有效的 JSON，请检查结构化输出设置或选择能按指令输出 JSON 的文本模型。";
            case "ConnectException", "UnresolvedAddressException" -> "无法连接模型服务，请检查平台地址和网络。";
            case "request timed out", "HTTP connect timed out" -> "模型服务连接或生成超时，请稍后重试。";
            case "AI response refused or did not complete" -> "模型拒绝了请求或输出提前结束，请检查模型和内容。";
            case "AI stream did not complete", "AI response did not complete" -> "模型输出中途结束，请检查平台连接后重试。";
            case "AI platform returned invalid JSON", "AI platform returned an error response" ->
                    "平台返回的内容与所选接口不符，请核对 API 基础地址和接口类型。";
            case "No article content available" -> "这篇文章没有可供摘要的正文，请检查原文是否可访问。";
            default -> detail.toLowerCase(Locale.ROOT).contains("interrupted") ? "上次摘要任务已中断，请重试。" : null;
        };
    }
}
