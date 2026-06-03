package app.store;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One row from the fetch_log table.
 */
public record FetchLogEntry(
        long id,
        String routePath,
        Instant fetchedAt,
        long durationMs,
        int itemCount,
        int newItemCount,
        String status,
        String errorDetail) {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public String formattedTime() {
        return FMT.format(fetchedAt);
    }

    public boolean isOk() {
        return "ok".equals(status);
    }
}
