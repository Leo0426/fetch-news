package app.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for cache hit and expiry behavior.
 */
class CacheServiceTest {
    /**
     * Verifies that repeated reads for the same key reuse the first loaded value.
     */
    @Test
    void returnsCachedValueForSameKey() throws Exception {
        CacheService cache = new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30));
        AtomicInteger calls = new AtomicInteger();

        String first = cache.getRouteFeed("example", () -> "value-" + calls.incrementAndGet());
        String second = cache.getRouteFeed("example", () -> "value-" + calls.incrementAndGet());

        assertEquals("value-1", first);
        assertEquals("value-1", second);
        assertEquals(1, calls.get());
    }

    /**
     * Verifies that an expired per-entry TTL causes the loader to run again.
     */
    @Test
    void reloadsValueAfterPerEntryTtlExpires() throws Exception {
        CacheService cache = new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30));
        AtomicInteger calls = new AtomicInteger();

        String first = cache.getRouteFeed("short", Duration.ofMillis(5), () -> "value-" + calls.incrementAndGet());
        Thread.sleep(20);
        String second = cache.getRouteFeed("short", Duration.ofMillis(5), () -> "value-" + calls.incrementAndGet());

        assertEquals("value-1", first);
        assertEquals("value-2", second);
        assertEquals(2, calls.get());
    }
}
