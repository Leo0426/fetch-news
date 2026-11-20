package app.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/** Bound password hashing work, including when many clients arrive through the same reverse proxy. */
final class PasswordLoginLimiter {
    private static final int MAX_ATTEMPTS = 12;
    private final Semaphore slots = new Semaphore(4);
    private final Cache<String, Integer> attempts = Caffeine.newBuilder().maximumSize(4096)
            .expireAfterWrite(Duration.ofMinutes(1)).build();

    boolean enter(String key) {
        if (!slots.tryAcquire()) return false;
        int count = attempts.asMap().merge(key, 1, Integer::sum);
        if (count <= MAX_ATTEMPTS) return true;
        slots.release();
        return false;
    }

    void finish(String key, boolean authenticated) {
        if (authenticated) attempts.invalidate(key);
        slots.release();
    }
}
