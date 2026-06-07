package app.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Fetches upstream content with Java's built-in HTTP client.
 */
public class DefaultFetchClient implements FetchClient {
    private static final int MAX_ATTEMPTS = 3;
    private final HttpClient client;
    private final Duration timeout;
    private final String userAgent;

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
                .followRedirects(HttpClient.Redirect.NORMAL)
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
    public String get(String url, Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", userAgent);
        extraHeaders.forEach(builder::setHeader);
        HttpRequest request = builder.GET().build();
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (status < 500 || attempt == MAX_ATTEMPTS) {
                    throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "upstream returned HTTP " + status);
                }
            } catch (RouteException e) {
                throw e;
            } catch (Exception e) {
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS) {
                    throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "upstream request failed: " + e.getMessage(), e);
                }
            }
        }
        throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "upstream request failed", lastFailure);
    }
}
