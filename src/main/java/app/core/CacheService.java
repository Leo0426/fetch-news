package app.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;

/**
 * Provides small TTL caches for final route feeds and fetched detail pages.
 */
public class CacheService {
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
        this.routeCache = Caffeine.newBuilder().maximumSize(500).build();
        this.detailCache = Caffeine.newBuilder().maximumSize(2000).build();
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
     * Returns the approximate combined number of entries across both caches.
     */
    public long totalSize() {
        return routeCache.estimatedSize() + detailCache.estimatedSize();
    }

    /**
     * Reads a cache entry, evicts expired values, and stores freshly loaded values.
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
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return (T) cached.value();
        }
        if (cached != null) {
            cache.invalidate(key);
        }
        T value = loader.call();
        cache.put(key, new CacheEntry(value, now.plus(ttl)));
        return value;
    }

    /**
     * Stores a cached value with its absolute expiration time.
     *
     * @param value cached value
     * @param expiresAt instant at which the value is no longer valid
     */
    private record CacheEntry(Object value, Instant expiresAt) {}
}
