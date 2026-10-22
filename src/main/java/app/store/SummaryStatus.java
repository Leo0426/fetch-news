package app.store;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Latest AI summarization status for one feed item.
 */
public record SummaryStatus(
        String itemLink,
        String status,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String model,
        String errorDetail) {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public boolean queued() { return "queued".equals(status); }
    public boolean running() { return "running".equals(status); }
    public boolean success() { return "success".equals(status); }
    public boolean error()   { return "error".equals(status); }

    public String errorMessage() {
        if (!error()) return null;
        if ("AI output token limit reached".equals(errorDetail)) {
            return "模型输出额度已用尽，请在 AI 设置中提高最大输出 Token 或切换模型后重试。";
        }
        return errorDetail != null && errorDetail.contains("interrupted")
                ? "上次摘要任务已中断，请重试。"
                : "摘要生成失败，请检查 AI Provider 配置或文章内容后重试。";
    }

    public String formattedStartedAt() {
        return startedAt != null ? FMT.format(startedAt) : "—";
    }

    public String formattedDuration() {
        if (durationMs == null) {
            if (startedAt == null || !running()) return "—";
            return formatMillis(Duration.between(startedAt, Instant.now()).toMillis());
        }
        return formatMillis(durationMs);
    }

    private static String formatMillis(long ms) {
        if (ms < 1000) return ms + " ms";
        return String.format("%.1f s", ms / 1000.0);
    }
}
