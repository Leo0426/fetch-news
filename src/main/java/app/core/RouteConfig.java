package app.core;

/**
 * Persisted runtime configuration for a route or mount alias.
 *
 * @param path exposed route path
 * @param sourcePath registered source route path used to handle requests
 * @param enabled whether the route is available to callers
 * @param routeCacheTtlSeconds TTL for final route feed cache entries
 * @param detailCacheTtlSeconds TTL for route-level upstream detail fetches
 * @param scheduleMinutes background fetch interval in minutes; 0 disables scheduling
 * @param scheduleCron Spring 6-field cron expression (sec min hr dom mon dow); takes precedence
 *                     over scheduleMinutes when non-null. Example: {@code "0 0 8 * * *"} = 8 AM daily.
 * @param feedUrl RSS/Atom feed URL for the generic {@code /rss} route; null for all other routes
 */
public record RouteConfig(
        String path,
        String sourcePath,
        boolean enabled,
        int routeCacheTtlSeconds,
        int detailCacheTtlSeconds,
        int scheduleMinutes,
        String scheduleCron,
        String feedUrl) {
    public static final int DEFAULT_ROUTE_CACHE_TTL_SECONDS = 300;
    public static final int DEFAULT_DETAIL_CACHE_TTL_SECONDS = 1800;

    /** Creates a configuration whose exposed path is also its source path, with no schedule. */
    public RouteConfig(String path, boolean enabled, int routeCacheTtlSeconds, int detailCacheTtlSeconds) {
        this(path, path, enabled, routeCacheTtlSeconds, detailCacheTtlSeconds, 0, null, null);
    }

    /** Creates a configuration with no schedule. */
    public RouteConfig(String path, String sourcePath, boolean enabled,
                       int routeCacheTtlSeconds, int detailCacheTtlSeconds) {
        this(path, sourcePath, enabled, routeCacheTtlSeconds, detailCacheTtlSeconds, 0, null, null);
    }

    /** Creates a configuration without a cron expression or feedUrl. */
    public RouteConfig(String path, String sourcePath, boolean enabled,
                       int routeCacheTtlSeconds, int detailCacheTtlSeconds, int scheduleMinutes) {
        this(path, sourcePath, enabled, routeCacheTtlSeconds, detailCacheTtlSeconds, scheduleMinutes, null, null);
    }

    /** Creates a configuration without a feedUrl. */
    public RouteConfig(String path, String sourcePath, boolean enabled,
                       int routeCacheTtlSeconds, int detailCacheTtlSeconds,
                       int scheduleMinutes, String scheduleCron) {
        this(path, sourcePath, enabled, routeCacheTtlSeconds, detailCacheTtlSeconds,
                scheduleMinutes, scheduleCron, null);
    }

    /** Validates route paths, cache TTL values, cron syntax, and feed URL format. */
    public RouteConfig {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "route path must start with /");
        }
        if (sourcePath == null || sourcePath.isBlank() || !sourcePath.startsWith("/")) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "source route path must start with /");
        }
        if (routeCacheTtlSeconds < 1) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "route cache TTL must be positive");
        }
        if (detailCacheTtlSeconds < 1) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "detail cache TTL must be positive");
        }
        if (scheduleMinutes < 0) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "schedule interval must be non-negative");
        }
        if (scheduleCron != null && !scheduleCron.isBlank()) {
            try {
                org.springframework.scheduling.support.CronExpression.parse(scheduleCron);
            } catch (IllegalArgumentException e) {
                throw new RouteException(RouteError.INVALID_PARAMETER,
                        "invalid cron expression: " + e.getMessage());
            }
        }
        scheduleCron = (scheduleCron != null && scheduleCron.isBlank()) ? null : scheduleCron;
        feedUrl      = (feedUrl      != null && feedUrl.isBlank())      ? null : feedUrl;
    }

    /** Returns whether this route can be scheduled (concrete path, no pattern params). */
    public boolean isSchedulable() {
        return !path.contains(":");
    }

    /** Returns whether this route has any schedule configured (interval or cron). */
    public boolean hasSchedule() {
        return isSchedulable() && (scheduleMinutes > 0 || scheduleCron != null);
    }

    /** Builds the default enabled configuration for a registered route. */
    public static RouteConfig defaults(String path) {
        return new RouteConfig(path, path, true,
                DEFAULT_ROUTE_CACHE_TTL_SECONDS, DEFAULT_DETAIL_CACHE_TTL_SECONDS, 0, null, null);
    }
}
