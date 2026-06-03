package app;

import app.core.CacheService;
import app.core.Feed;
import app.core.FeedItem;
import app.core.FeedRenderer;
import app.core.JsonRenderer;
import app.core.RouteConfig;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteMatch;
import app.core.RouteRegistry;
import app.core.RssRenderer;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles public feed requests and renders route output as RSS or JSON.
 */
@RestController
public class RssLiteResource {
    private static final Logger log = LoggerFactory.getLogger(RssLiteResource.class);

    private final CacheService cacheService;
    private final RouteRegistry registry;
    private final AppRuntime runtime;
    private final FeedFetcher feedFetcher;
    private final FeedStore feedStore;
    private final Map<String, FeedRenderer> renderers;

    /**
     * Creates the public feed resource with persistence support.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RssLiteResource(AppRuntime runtime, ObjectMapper objectMapper, FeedStore feedStore) {
        this.runtime = runtime;
        this.cacheService = runtime.cacheService();
        this.registry = runtime.routeRegistry();
        this.feedFetcher = new FeedFetcher(feedStore);
        this.feedStore = feedStore;
        RssRenderer rss = new RssRenderer();
        JsonRenderer json = new JsonRenderer(objectMapper);
        this.renderers = Map.of(rss.format(), rss, json.format(), json);
    }

    /** Convenience constructor for unit tests; disables persistence. */
    RssLiteResource(AppRuntime runtime, ObjectMapper objectMapper) {
        this(runtime, objectMapper, null);
    }

    /**
     * Resolves a route, applies common parameters, and returns the requested feed format.
     *
     * @param routePath path captured from the root resource
     * @param format optional output format, either {@code rss} or {@code json}
     * @param limit optional maximum number of items to include
     * @return rendered feed response or structured error response
     */
    @GetMapping("/{*routePath}")
    public ResponseEntity<?> get(
            @PathVariable(required = false) String routePath,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String limit) {
        String path = normalizePath(routePath);
        // Redirect root and legacy /admin URL to the index UI
        if ("/".equals(path) || "/admin".equals(path)) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, "/index")
                    .build();
        }
        Map<String, String> query = new LinkedHashMap<>();
        if (format != null) query.put("format", format);
        if (limit != null) query.put("limit", limit);
        log.info("[req] {} format={} limit={}", path, format == null ? "rss" : format, limit == null ? "-" : limit);
        try {
            String outputFormat = format == null || format.isBlank() ? "rss" : format;
            int itemLimit = parseLimit(limit);
            RouteConfig config = runtime.routeConfigStore().get(path);
            if (!config.enabled()) {
                throw new RouteException(RouteError.ROUTE_DISABLED, "route is disabled: " + path);
            }
            RouteMatch match = resolveForPath(path, config.sourcePath());
            RouteContext context = new RouteContext(
                    path,
                    match.context().pathParams(),
                    query,
                    Duration.ofSeconds(config.detailCacheTtlSeconds()),
                    config.feedUrl());
            String cacheKey = path
                    + "?source=" + config.sourcePath()
                    + "&format=" + outputFormat
                    + "&limit=" + itemLimit
                    + "&params=" + context.pathParams();
            boolean[] cacheHit = {true};
            Feed feed = cacheService.getRouteFeed(
                    cacheKey,
                    Duration.ofSeconds(config.routeCacheTtlSeconds()),
                    () -> { cacheHit[0] = false; return feedFetcher.fetch(match, context, path, itemLimit); });
            log.info("[req] {} → {} ({} items)", path, cacheHit[0] ? "cache" : "fetched", feed.items().size());
            if (feed.items().isEmpty()) {
                throw new RouteException(RouteError.EMPTY_FEED);
            }
            if (feedStore != null) {
                try {
                    List<FeedItem> merged = feedStore.mergeExtracts(feed.items());
                    if (merged != feed.items()) {
                        feed = new Feed(feed.title(), feed.link(), feed.description(), merged);
                    }
                } catch (Exception e) {
                    log.warn("mergeExtracts failed for {}: {}", path, e.getMessage());
                }
            }
            return render(feed, outputFormat, config.routeCacheTtlSeconds());
        } catch (RouteException e) {
            log.warn("[req] {} → error {} {}", path, e.error(), e.getMessage());
            if (feedStore != null && isRecoverableUpstreamError(e)) {
                try {
                    java.util.Optional<Feed> offline = feedStore.offlineFeed(path);
                    if (offline.isPresent()) {
                        log.info("[req] {} → offline fallback", path);
                        return render(offline.get(), format == null ? "rss" : format, 60);
                    }
                } catch (Exception dbEx) {
                    log.warn("offline fallback failed for {}: {}", path, dbEx.getMessage());
                }
            }
            return error(e.error(), e.getMessage());
        } catch (Exception e) {
            log.warn("[req] {} → renderer error {}", path, e.getMessage());
            return error(RouteError.RENDERER_FAILED, e.getMessage());
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves a route for serving: tries the concrete request path first so that
     * saved instances of parameterized routes (e.g. /github/releases/anthropics/claude)
     * extract the correct path params. Falls back to sourcePath for plain mount aliases
     * whose path doesn't match any registered pattern.
     */
    private RouteMatch resolveForPath(String requestPath, String sourcePath) {
        try {
            return registry.resolve(requestPath);
        } catch (RouteException e) {
            if (e.error() != RouteError.ROUTE_NOT_FOUND) throw e;
            return registry.resolve(sourcePath);
        }
    }

    private ResponseEntity<?> render(Feed feed, String outputFormat, int cacheTtlSeconds) {
        FeedRenderer renderer = renderers.get(outputFormat);
        if (renderer == null) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "unsupported format: " + outputFormat);
        }
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(renderer.contentType()))
                    .header(HttpHeaders.CACHE_CONTROL,
                            "public, max-age=" + cacheTtlSeconds + ", stale-while-revalidate=60")
                    .body(renderer.render(feed));
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.RENDERER_FAILED, e.getMessage(), e);
        }
    }

    private static boolean isRecoverableUpstreamError(RouteException e) {
        return switch (e.error()) {
            case UPSTREAM_REQUEST_FAILED, UPSTREAM_INVALID_CONTENT,
                 PARSER_FAILED, EMPTY_FEED -> true;
            default -> false;
        };
    }

    private int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) return 0;
        try {
            int parsed = Integer.parseInt(limit);
            if (parsed < 1) throw new RouteException(RouteError.INVALID_PARAMETER, "limit must be positive");
            return parsed;
        } catch (NumberFormatException e) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "limit must be a number");
        }
    }

    private String normalizePath(String routePath) {
        if (routePath == null || routePath.isBlank()) return "/";
        return routePath.startsWith("/") ? routePath : "/" + routePath;
    }

    private ResponseEntity<Map<String, String>> error(RouteError error, String detail) {
        return ResponseEntity.status(HttpStatus.valueOf(error.statusCode()))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.LOCATION, URI.create("/").toString())
                .body(Map.of("error", error.message(), "detail", detail));
    }
}
