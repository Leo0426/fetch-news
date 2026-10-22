package app.routes;

import app.core.Feed;
import app.core.FetchClient;
import app.core.FetchDeferredException;
import app.core.FetchResponse;
import app.core.RetryAfter;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import app.store.FeedRefreshState;
import app.store.FeedStore;
import app.store.HttpFeedSnapshot;
import app.support.FeedFingerprint;
import app.support.FeedParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class GenericRssRoute implements RouteHandler {

    private final FetchClient client;
    private final FeedStore store;
    private final Clock clock;

    public GenericRssRoute(FetchClient client) {
        this(client, null);
    }

    public GenericRssRoute(FetchClient client, FeedStore store) {
        this(client, store, Clock.systemUTC());
    }

    public GenericRssRoute(FetchClient client, FeedStore store, Clock clock) {
        this.client = client;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public Feed handle(RouteContext ctx) throws Exception {
        String url = validateUrl(feedUrl(ctx));
        FeedRefreshState previous = store == null ? null : store.loadFeedRefreshState(ctx.path(), url);
        if (previous != null && previous.nextRetryAt() != null
                && previous.nextRetryAt().isAfter(clock.instant())) {
            throw new FetchDeferredException(previous.nextRetryAt());
        }
        HttpFeedSnapshot snapshot = store == null ? null : store.loadHttpFeedSnapshot(url);
        Feed cachedFeed = null;
        if (snapshot != null) {
            try {
                cachedFeed = parse(snapshot.body(), url);
            } catch (RouteException invalidSnapshot) {
                store.deleteHttpFeedSnapshot(url);
                snapshot = null;
            }
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (snapshot != null) {
            if (snapshot.etag() != null) headers.put("If-None-Match", snapshot.etag());
            if (snapshot.lastModified() != null) headers.put("If-Modified-Since", snapshot.lastModified());
        }
        long start = System.nanoTime();
        FetchResponse response = null;
        Feed feed;
        String xml;
        try {
            response = client.fetch(url, headers);
            if (response.statusCode() == 304 && snapshot == null) {
                response = client.fetch(url, Map.of());
            }
            if (store != null && !allowsSnapshot(response)) {
                store.deleteHttpFeedSnapshot(url);
            }
            if (response.statusCode() == 304 && snapshot != null) {
                xml = snapshot.body();
                feed = cachedFeed;
            } else if (response.statusCode() == 200) {
                xml = response.body();
                feed = parse(xml, url);
            } else {
                throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED,
                        "upstream returned HTTP " + response.statusCode());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (SQLException persistenceFailure) {
            throw persistenceFailure;
        } catch (Exception failure) {
            RouteException error = failure instanceof RouteException routeError ? routeError
                    : new RouteException(RouteError.UPSTREAM_REQUEST_FAILED,
                            "upstream request failed: " + failure.getClass().getSimpleName(), failure);
            recordFailure(ctx.path(), url, previous, response, start, error);
            throw error;
        }
        if (store != null) {
            Instant now = clock.instant();
            boolean unchanged = response.statusCode() == 304;
            if (allowsSnapshot(response)) {
                store.saveHttpFeedSnapshot(new HttpFeedSnapshot(url, xml,
                        validator(response, "ETag", unchanged ? snapshot.etag() : null),
                        validator(response, "Last-Modified", unchanged ? snapshot.lastModified() : null)));
            } else {
                store.deleteHttpFeedSnapshot(url);
            }
            String fingerprint = FeedFingerprint.of(feed);
            boolean changed = previous == null || !fingerprint.equals(previous.contentFingerprint());
            store.saveFeedRefreshState(new FeedRefreshState(ctx.path(), url, now, now,
                    changed || previous == null ? now : previous.lastChangedAt(), null, 0,
                    response.statusCode(), responseBytes(response), elapsedMillis(start), null, fingerprint));
        }
        return feed;
    }

    public String feedUrl(RouteContext context) {
        return context.feedUrl();
    }

    private void recordFailure(String path, String url, FeedRefreshState previous,
                               FetchResponse response, long start, RouteException failure) {
        if (store == null) return;
        Instant now = clock.instant();
        int failures = previous == null ? 1 : Math.min(previous.consecutiveFailures(), Integer.MAX_VALUE - 1) + 1;
        long delay = Math.min(3600, 60L << Math.min(failures - 1, 6));
        Instant retryAt = now.plusSeconds(delay);
        Instant retryAfter = response == null ? null : RetryAfter.parse(response.header("Retry-After"), now);
        if (retryAfter != null && retryAfter.isAfter(retryAt)) retryAt = retryAfter;
        try {
            store.saveFeedRefreshState(new FeedRefreshState(path, url, now,
                    previous == null ? null : previous.lastSuccessAt(),
                    previous == null ? null : previous.lastChangedAt(), retryAt, failures,
                    response == null ? 0 : response.statusCode(), responseBytes(response),
                    elapsedMillis(start), failure.getMessage(),
                    previous == null ? null : previous.contentFingerprint()));
        } catch (SQLException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
    }

    private static String validateUrl(String url) {
        if (url != null && !url.isBlank()) {
            try {
                URI uri = URI.create(url);
                if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && uri.getUserInfo() == null) return url;
            } catch (IllegalArgumentException ignored) {
                // Report configuration errors before invoking the HTTP client.
            }
        }
        throw new RouteException(RouteError.INVALID_PARAMETER,
                "feedUrl must be an absolute HTTP(S) URL without embedded credentials");
    }

    private static boolean allowsSnapshot(FetchResponse response) {
        if (response.header("Vary") != null || response.header("Set-Cookie") != null) return false;
        return response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("Cache-Control"))
                .flatMap(entry -> entry.getValue().stream())
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(value -> value.trim().toLowerCase(Locale.ROOT).split("=", 2)[0].trim())
                .noneMatch(value -> value.equals("no-store") || value.equals("private"));
    }

    private static String validator(FetchResponse response, String name, String previous) {
        String value = response.header(name);
        return value == null ? previous : value.isBlank() ? null : value;
    }

    private static long responseBytes(FetchResponse response) {
        return response == null || response.statusCode() == 304 || response.body() == null
                ? 0 : response.body().getBytes(StandardCharsets.UTF_8).length;
    }

    private static long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static Feed parse(String xml, String url) {
        Feed feed = FeedParser.parse(xml, url);
        if (feed.items().isEmpty()) {
            throw new RouteException(RouteError.EMPTY_FEED);
        }
        return feed;
    }
}
