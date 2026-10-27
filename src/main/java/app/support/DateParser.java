package app.support;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Small helpers for parsing source dates.
 */
public final class DateParser {
    // Some Chinese feeds (36氪) publish "2026-10-27 18:54:53  +0800" instead of RFC 822.
    private static final DateTimeFormatter LOCAL_WITH_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    /**
     * Prevents construction of this utility class.
     */
    private DateParser() {}

    /**
     * Parses an ISO-8601 instant or returns {@code null} for blank or invalid input.
     *
     * @param value raw date value
     * @return parsed instant or {@code null}
     */
    public static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * Parses an ISO-8601 or RFC 1123/RFC 822 date string, returning {@code null} on failure.
     * RSS 2.0 uses RFC 822 pubDate; Atom uses ISO 8601.
     *
     * @param value raw date string
     * @return parsed instant or {@code null}
     */
    public static Instant parseDateOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Instant iso = parseInstantOrNull(value);
        if (iso != null) {
            return iso;
        }
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through to the space-separated form
        }
        try {
            return ZonedDateTime.parse(value.trim().replaceAll("\\s+", " "), LOCAL_WITH_OFFSET).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
