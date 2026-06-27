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

    public boolean running() { return "running".equals(status); }
    public boolean success() { return "success".equals(status); }
    public boolean error()   { return "error".equals(status); }

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
