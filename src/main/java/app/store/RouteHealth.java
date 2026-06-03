package app.store;

import java.time.Duration;
import java.time.Instant;

/**
 * Health statistics for a single route, derived from its last 10 fetch attempts.
 */
public record RouteHealth(
        String routePath,
        Instant lastFetchAt,
        String lastStatus,
        String lastError,
        int recentRuns,
        int recentOk,
        long avgDurationMs) {

    public boolean everFetched()  { return lastFetchAt != null; }
    public boolean healthy()      { return "ok".equals(lastStatus); }
    public int successPercent()   { return recentRuns > 0 ? recentOk * 100 / recentRuns : 0; }

    public String formattedAge() {
        if (lastFetchAt == null) return "从未抓取";
        long minutes = Duration.between(lastFetchAt, Instant.now()).toMinutes();
        if (minutes < 1)  return "刚刚";
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24)   return hours + " 小时前";
        return hours / 24 + " 天前";
    }

    public String formattedDuration() {
        if (!everFetched() || avgDurationMs == 0) return "—";
        if (avgDurationMs < 1000) return avgDurationMs + " ms";
        return String.format("%.1f s", avgDurationMs / 1000.0);
    }

    /** CSS color for the success-rate progress bar. */
    public String barColor() {
        if (successPercent() >= 80) return "var(--green)";
        if (successPercent() >= 50) return "#f59e0b";
        return "var(--warn)";
    }

    /** Placeholder for scheduled routes that have never been fetched. */
    public static RouteHealth neverFetched(String routePath) {
        return new RouteHealth(routePath, null, null, null, 0, 0, 0);
    }
}
