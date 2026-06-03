package app;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Exposes application-level metadata (version, uptime) for the admin UI.
 */
@Component
public class AppInfo {
    public static final String VERSION = "0.2.0";
    private final Instant startTime = Instant.now();

    public String version() {
        return VERSION;
    }

    public String formattedUptime() {
        Duration d = Duration.between(startTime, Instant.now());
        long h = d.toHours();
        long m = d.toMinutesPart();
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }
}
