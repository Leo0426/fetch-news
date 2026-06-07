package app.core;

import java.util.Map;

/**
 * Minimal abstraction for outbound HTTP fetches used by routes.
 */
@FunctionalInterface
public interface FetchClient {
    /**
     * Fetches the response body for a URL.
     *
     * @param url absolute URL to request
     * @return response body as text
     * @throws Exception when the request cannot be completed
     */
    String get(String url) throws Exception;

    /**
     * Fetches the response body for a URL with additional request headers.
     * Implementations that do not support extra headers fall back to {@link #get(String)}.
     */
    default String get(String url, Map<String, String> extraHeaders) throws Exception {
        return get(url);
    }
}
