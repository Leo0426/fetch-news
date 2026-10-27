package app.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;

/**
 * Fetches upstream content with Java's built-in HTTP client.
 */
public class DefaultFetchClient implements FetchClient {
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_REDIRECTS = 5;
    /** Upper bound for a response body, before and after gzip decoding. */
    static final int MAX_BODY_BYTES = 16 << 20;
    private static final HttpResponse.BodyHandler<byte[]> BODY =
            HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BODY_BYTES);
    private final HttpClient client;
    private final Duration timeout;
    private final String userAgent;
    private final ConcurrentMap<String, Semaphore> hostPermits = new ConcurrentHashMap<>();

    /**
     * Creates a fetch client with the default timeout and user agent.
     */
    public DefaultFetchClient() {
        this(Duration.ofSeconds(10),
                "Mozilla/5.0 (compatible; fetch-news/1.0; +https://github.com)");
    }

    /**
     * Creates a fetch client with explicit request settings.
     *
     * @param timeout connection and request timeout
     * @param userAgent user agent header sent to upstream servers
     */
    public DefaultFetchClient(Duration timeout, String userAgent) {
        this.timeout = timeout;
        this.userAgent = userAgent;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Fetches a URL body as text, retrying transient failures.
     *
     * @param url absolute URL to fetch
     * @return response body
     * @throws Exception when the upstream request fails
     */
    @Override
    public String get(String url) throws Exception {
        return get(url, Map.of());
    }

    @Override
    public FetchResponse fetch(String url, Map<String, String> extraHeaders) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        Map<String, String> headers = extraHeaders;
        for (int redirects = 0; ; redirects++) {
            FetchResponse response = send(uri, headers);
            if (!isRedirect(response.statusCode()) || redirects == MAX_REDIRECTS) {
                return response;
            }
            URI next = redirectUri(uri, response.header("Location"));
            if (next == null) {
                return response;
            }
            if (!sameOrigin(uri, next)) {
                headers = Map.of();
            }
            uri = next;
        }
    }

    private FetchResponse send(URI uri, Map<String, String> extraHeaders) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "gzip");
        extraHeaders.forEach(builder::setHeader);
        HttpRequest request = builder.GET().build();
        String host = request.uri().getHost().toLowerCase(Locale.ROOT);
        Semaphore permits = hostPermits.computeIfAbsent(host, ignored -> new Semaphore(2, true));
        try {
            permits.acquire();
            try {
                HttpResponse<byte[]> response = await(client.sendAsync(request, BODY));
                return new FetchResponse(response.statusCode(), response.headers().map(), decode(response));
            } finally {
                permits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * The request timeout only covers the response headers, so a server that stalls mid-body would hold the
     * host permit forever; this bounds the whole exchange.
     */
    private HttpResponse<byte[]> await(CompletableFuture<HttpResponse<byte[]>> pending)
            throws IOException, InterruptedException {
        Duration deadline = timeout.multipliedBy(3);
        try {
            return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new HttpTimeoutException("response not completed within " + deadline.toSeconds() + " s");
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException(e.getCause());
        }
    }

    private static String decode(HttpResponse<byte[]> response) throws IOException {
        byte[] body = response.body();
        HttpHeaders headers = response.headers();
        if (body.length > 0 && headers.firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip")) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = in.readNBytes(MAX_BODY_BYTES + 1);
            }
            if (body.length > MAX_BODY_BYTES) {
                throw new IOException("decoded body exceeds " + MAX_BODY_BYTES + " bytes");
            }
        }
        return new String(body, charset(headers.firstValue("Content-Type").orElse("")));
    }

    private static Charset charset(String contentType) {
        for (String parameter : contentType.split(";")) {
            String[] pair = parameter.trim().split("=", 2);
            if (pair.length == 2 && pair[0].trim().equalsIgnoreCase("charset")) {
                try {
                    return Charset.forName(pair[1].trim().replace("\"", ""));
                } catch (IllegalArgumentException unsupported) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    @Override
    public String get(String url, Map<String, String> extraHeaders) throws Exception {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                FetchResponse response = fetch(url, extraHeaders);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (status < 500 || status >= 600 || status == 503 || attempt == MAX_ATTEMPTS) {
                    throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "upstream returned HTTP " + status);
                }
            } catch (IOException e) {
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS) {
                    throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "upstream request failed: " + e.getMessage(), e);
                }
            }
            try {
                Thread.sleep(250L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "upstream request failed", lastFailure);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static URI redirectUri(URI uri, String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            URI next = uri.resolve(location);
            boolean http = "http".equalsIgnoreCase(next.getScheme());
            boolean https = "https".equalsIgnoreCase(next.getScheme());
            if (next.getHost() == null || (!http && !https)
                    || ("https".equalsIgnoreCase(uri.getScheme()) && !https)) {
                return null;
            }
            return next;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
