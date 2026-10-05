package app.store;

import java.time.Instant;

public record FeedRefreshState(
        String routePath,
        String feedUrl,
        Instant lastCheckedAt,
        Instant lastSuccessAt,
        Instant lastChangedAt,
        Instant nextRetryAt,
        int consecutiveFailures,
        int lastHttpStatus,
        long responseBytes,
        long lastDurationMs,
        String lastError,
        String contentFingerprint) {
    public FeedRefreshState(String routePath, String feedUrl, Instant lastCheckedAt, Instant lastSuccessAt,
                            Instant lastChangedAt, Instant nextRetryAt, int consecutiveFailures,
                            int lastHttpStatus, long responseBytes, long lastDurationMs, String lastError) {
        this(routePath, feedUrl, lastCheckedAt, lastSuccessAt, lastChangedAt, nextRetryAt,
                consecutiveFailures, lastHttpStatus, responseBytes, lastDurationMs, lastError, null);
    }
}
