package app.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;

/**
 * Provides small TTL caches for final route feeds and fetched detail pages.
 *
 * <p>Both are bounded by an estimate of their text size, not entry count: detail pages are whole upstream
 * HTML or JSON documents, and a few thousand of them would not fit the heap.
 */
public class CacheService {
    static final int ROUTE_CACHE_MAX_CHARS = 16 << 20;
    static final int DETAIL_CACHE_MAX_CHARS = 32 << 20;

    private final Cache<String, CacheEntry> routeCache;
    private final Cache<String, CacheEntry> detailCache;
    private final Duration defaultRouteTtl;
    private final Duration defaultDetailTtl;

    /**
     * Creates a cache service with the default route and detail TTL values.
     */
    public CacheService() {
        this(Duration.ofMinutes(5), Duration.ofMinutes(30));
    }

    /**
     * Creates a cache service with explicit default TTL values.
     *
     * @param routeTtl default TTL for fully rendered route feeds
     * @param detailTtl default TTL for upstream detail page fetches
     */
    public CacheService(Duration routeTtl, Duration detailTtl) {
        this.defaultRouteTtl = routeTtl;
        this.defaultDetailTtl = detailTtl;
        this.routeCache = boundedCache(ROUTE_CACHE_MAX_CHARS);
        this.detailCache = boundedCache(DETAIL_CACHE_MAX_CHARS);
    }

    private static Cache<String, CacheEntry> boundedCache(int maxChars) {
        return Caffeine.newBuilder()
                .maximumWeight(maxChars)
                .weigher((String key, CacheEntry entry) -> key.length() + estimatedChars(entry.value()))
                // Entries carry their own TTL; expire them then instead of keeping them until evicted.
                .expireAfter(Expiry.writing((String key, CacheEntry entry) -> remaining(entry)))
                // Evict on the calling thread so a burst of large pages cannot outrun the size bound.
                .executor(Runnable::run)
                .build();
    }

    private static Duration remaining(CacheEntry entry) {
        Duration left = Duration.between(Instant.now(), entry.expiresAt());
        return left.isNegative() ? Duration.ZERO : left;
    }

    static int estimatedChars(Object value) {
        if (value instanceof String text) return text.length();
        if (value instanceof Feed feed) {
            long chars = 0;
            for (FeedItem item : feed.items()) {
                chars += 256 + length(item.title()) + length(item.description());
            }
            return (int) Math.min(Integer.MAX_VALUE, chars);
        }
        return 1024;
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    /**
     * Returns a cached route feed or loads it with the default route TTL.
     *
     * @param key cache key
     * @param loader value loader used on cache miss
     * @param <T> cached value type
     * @return cached or loaded value
     * @throws Exception when the loader fails
     */
    public <T> T getRouteFeed(String key, Callable<T> loader) throws Exception {
        return getRouteFeed(key, defaultRouteTtl, loader);
    }

    /**
     * Returns a cached route feed or loads it with a per-entry TTL.
     *
     * @param key cache key
     * @param ttl TTL for the loaded value
     * @param loader value loader used on cache miss
     * @param <T> cached value type
     * @return cached or loaded value
     * @throws Exception when the loader fails
     */
    public <T> T getRouteFeed(String key, Duration ttl, Callable<T> loader) throws Exception {
        return get(routeCache, key, ttl, loader);
    }

    /**
     * Returns a cached detail page value or loads it with the default detail TTL.
     *
     * @param key cache key
     * @param loader value loader used on cache miss
     * @param <T> cached value type
     * @return cached or loaded value
     * @throws Exception when the loader fails
     */
    public <T> T getDetailPage(String key, Callable<T> loader) throws Exception {
        return getDetailPage(key, defaultDetailTtl, loader);
    }

    /**
     * Returns a cached detail page value or loads it with a per-entry TTL.
     *
     * @param key cache key
     * @param ttl TTL for the loaded value
     * @param loader value loader used on cache miss
     * @param <T> cached value type
     * @return cached or loaded value
     * @throws Exception when the loader fails
     */
    public <T> T getDetailPage(String key, Duration ttl, Callable<T> loader) throws Exception {
        return get(detailCache, key, ttl, loader);
    }

    /**
     * Reads a cache entry and atomically reloads missing or expired values for each key.
     *
     * @param cache target cache
     * @param key cache key
     * @param ttl TTL for newly loaded values
     * @param loader value loader used on cache miss
     * @param <T> cached value type
     * @return cached or loaded value
     * @throws Exception when the loader fails
     */
    @SuppressWarnings("unchecked")
    private <T> T get(Cache<String, CacheEntry> cache, String key, Duration ttl, Callable<T> loader) throws Exception {
        CacheEntry cached = cache.getIfPresent(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return (T) cached.value();
        }
        try {
            CacheEntry loaded = cache.asMap().compute(key, (ignored, current) -> {
                if (current != null && current.expiresAt().isAfter(Instant.now())) {
                    return current;
                }
                try {
                    T value = loader.call();
                    return new CacheEntry(value, Instant.now().plus(ttl));
                } catch (Exception e) {
                    throw new CacheLoadException(e);
                }
            });
            return (T) loaded.value();
        } catch (CacheLoadException e) {
            throw (Exception) e.getCause();
        }
    }

    private static final class CacheLoadException extends RuntimeException {
        private CacheLoadException(Exception cause) {
            super(cause);
        }
    }

    /**
     * Stores a cached value with its absolute expiration time.
     *
     * @param value cached value
     * @param expiresAt instant at which the value is no longer valid
     */
    private record CacheEntry(Object value, Instant expiresAt) {}
}
