package app.core;

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
}
