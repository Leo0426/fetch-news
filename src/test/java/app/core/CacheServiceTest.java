package app.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    @Test
    void detailCacheEvictsByTextSizeRatherThanEntryCount() throws Exception {
        CacheService cache = new CacheService(Duration.ofMinutes(5), Duration.ofMinutes(30));
        AtomicInteger loads = new AtomicInteger();
        int pageChars = 1 << 20;
        int pages = CacheService.DETAIL_CACHE_MAX_CHARS / pageChars + 8;
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < pages; i++) {
                cache.getDetailPage("page-" + i, () -> {
                    loads.incrementAndGet();
                    return "x".repeat(pageChars);
                });
            }
        }

        assertTrue(loads.get() > pages, "pages beyond the size limit must have been evicted and reloaded");
    }

    @Test
    void estimatesFeedsByTheirItemText() {
        Feed feed = new Feed("t", "https://example.com", "d", List.of(
                new FeedItem("title", "https://example.com/1", "x".repeat(1000), null, null, List.of(), null)));

        assertEquals(256 + 5 + 1000, CacheService.estimatedChars(feed));
        assertEquals(3, CacheService.estimatedChars("abc"));
    }

    @Test
    void sharesConcurrentRouteLoads() throws Exception {
        CacheService cache = new CacheService();

        assertSharesConcurrentLoads(cache::getRouteFeed);
    }

    @Test
    void sharesConcurrentDetailLoads() throws Exception {
        CacheService cache = new CacheService();

        assertSharesConcurrentLoads(cache::getDetailPage);
    }

    @Test
    void sharesConcurrentReloadsAfterExpiry() throws Exception {
        CacheService cache = new CacheService();
        cache.getRouteFeed("shared", Duration.ofMillis(5), () -> "expired");
        Thread.sleep(20);

        assertSharesConcurrentLoads(cache::getRouteFeed);
    }

    @Test
    void loadsDifferentKeysConcurrently() throws Exception {
        CacheService cache = new CacheService();
        CountDownLatch bothLoading = new CountDownLatch(2);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> cache.getRouteFeed("first", () -> {
                bothLoading.countDown();
                assertTrue(bothLoading.await(2, TimeUnit.SECONDS), "unrelated keys must load concurrently");
                return "first value";
            }));
            var second = executor.submit(() -> cache.getRouteFeed("second", () -> {
                bothLoading.countDown();
                assertTrue(bothLoading.await(2, TimeUnit.SECONDS), "unrelated keys must load concurrently");
                return "second value";
            }));

            assertEquals("first value", first.get(5, TimeUnit.SECONDS));
            assertEquals("second value", second.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void startsRouteTtlAfterLoadingFinishes() throws Exception {
        CacheService cache = new CacheService();

        assertTtlStartsAfterLoading(cache::getRouteFeed);
    }

    @Test
    void startsDetailTtlAfterLoadingFinishes() throws Exception {
        CacheService cache = new CacheService();

        assertTtlStartsAfterLoading(cache::getDetailPage);
    }

    @Test
    void preservesCheckedFailureAndRetriesNextLoad() throws Exception {
        CacheService cache = new CacheService();
        IOException failure = new IOException("upstream connection closed");
        AtomicInteger calls = new AtomicInteger();

        IOException thrown = assertThrows(IOException.class, () -> cache.getRouteFeed("retry", () -> {
            calls.incrementAndGet();
            throw failure;
        }));
        String recovered = cache.getRouteFeed("retry", () -> "value-" + calls.incrementAndGet());
        String cached = cache.getRouteFeed("retry", () -> "value-" + calls.incrementAndGet());

        assertSame(failure, thrown);
        assertEquals("value-2", recovered);
        assertEquals("value-2", cached);
        assertEquals(2, calls.get());
    }

    @Test
    void preservesRouteFailureAndRetriesNextLoad() throws Exception {
        CacheService cache = new CacheService();
        RouteException failure = new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "HTTP 503");

        RouteException thrown = assertThrows(RouteException.class,
                () -> cache.getDetailPage("retry", () -> { throw failure; }));

        assertSame(failure, thrown);
        assertEquals("recovered", cache.getDetailPage("retry", () -> "recovered"));
    }

    private static void assertSharesConcurrentLoads(CacheAccess cache) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstLoading = new CountDownLatch(1);
        CountDownLatch secondCalling = new CountDownLatch(1);
        CountDownLatch duplicateLoading = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        Callable<String> loader = () -> {
            int call = calls.incrementAndGet();
            firstLoading.countDown();
            if (call > 1) duplicateLoading.countDown();
            assertTrue(releaseLoader.await(5, TimeUnit.SECONDS), "loader must be released");
            return "value-" + call;
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> cache.load("shared", Duration.ofMinutes(5), loader));
            try {
                assertTrue(firstLoading.await(2, TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    secondCalling.countDown();
                    return cache.load("shared", Duration.ofMinutes(5), loader);
                });
                assertTrue(secondCalling.await(2, TimeUnit.SECONDS));
                duplicateLoading.await(150, TimeUnit.MILLISECONDS);
                releaseLoader.countDown();

                assertEquals("value-1", first.get(5, TimeUnit.SECONDS));
                assertEquals("value-1", second.get(5, TimeUnit.SECONDS));
                assertEquals(1, calls.get());
            } finally {
                releaseLoader.countDown();
            }
        }
    }

    private static void assertTtlStartsAfterLoading(CacheAccess cache) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Duration ttl = Duration.ofMillis(150);
        String first = cache.load("slow", ttl, () -> {
            Thread.sleep(300);
            return "value-" + calls.incrementAndGet();
        });
        String second = cache.load("slow", ttl, () -> "value-" + calls.incrementAndGet());

        assertEquals("value-1", first);
        assertEquals("value-1", second);
        assertEquals(1, calls.get());
    }

    @FunctionalInterface
    private interface CacheAccess {
        String load(String key, Duration ttl, Callable<String> loader) throws Exception;
    }
}
