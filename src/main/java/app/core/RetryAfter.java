package app.core;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class RetryAfter {
    private RetryAfter() {
    }

    public static Instant parse(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            if (trimmed.matches("[0-9]+")) {
                return now.plusSeconds(Long.parseLong(trimmed));
            }
            Instant parsed = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return parsed.isBefore(now) ? now : parsed;
        } catch (DateTimeException | ArithmeticException | NumberFormatException e) {
            return null;
        }
    }
}
