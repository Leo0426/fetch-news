package app.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DateParserTest {
    @Test
    void parsesIsoRfc822AndSpaceSeparatedDatesWithOffset() {
        Instant expected = Instant.parse("2026-10-27T10:54:53Z");
        assertEquals(expected, DateParser.parseDateOrNull("2026-10-27T10:54:53Z"));
        assertEquals(expected, DateParser.parseDateOrNull("Tue, 27 Oct 2026 10:54:53 GMT"));
        assertEquals(expected, DateParser.parseDateOrNull("2026-10-27 18:54:53  +0800"));
        assertEquals(expected, DateParser.parseDateOrNull(" 2026-10-27 18:54:53 +0800 "));
    }

    @Test
    void returnsNullForBlankOrUnknownDates() {
        assertNull(DateParser.parseDateOrNull(null));
        assertNull(DateParser.parseDateOrNull(" "));
        assertNull(DateParser.parseDateOrNull("2026-10-27 18:54:53"));
        assertNull(DateParser.parseDateOrNull("yesterday"));
    }
}
