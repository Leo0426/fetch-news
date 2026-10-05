package app.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RetryAfterTest {
    @Test
    void parsesNonnegativeDelaySeconds() {
        Instant now = Instant.parse("2026-10-03T12:00:00Z");

        assertEquals(now.plusSeconds(120), RetryAfter.parse("120", now));
        assertEquals(now, RetryAfter.parse("0", now));
        assertEquals(now.plusSeconds(120), RetryAfter.parse(" 120 ", now));
    }

    @Test
    void parsesHttpDatesAndClampsPastDates() {
        Instant now = Instant.parse("2026-10-03T12:00:00Z");

        assertEquals(now.plusSeconds(120), RetryAfter.parse("Sat, 3 Oct 2026 12:02:00 GMT", now));
        assertEquals(now, RetryAfter.parse("Fri, 2 Oct 2026 12:00:00 GMT", now));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "-1", "+1", "1.5", "tomorrow", "120 seconds",
            "999999999999999999999999999999999", "9223372036854775807"})
    void ignoresMalformedAndOverflowingValues(String value) {
        assertNull(RetryAfter.parse(value, Instant.parse("2026-10-03T12:00:00Z")));
    }

    @Test
    void ignoresDelayThatOverflowsInstant() {
        assertNull(RetryAfter.parse("1", Instant.MAX));
    }
}
