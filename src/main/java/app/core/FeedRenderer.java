package app.core;

/**
 * Renders a normalized feed into a specific output format.
 *
 * <p>Implementations declare their format name and HTTP content type so that
 * the feed resource can look up the right renderer from the {@code format}
 * query parameter without any if/else dispatch.
 */
public interface FeedRenderer {
    /** Format name matched against the {@code ?format=} query parameter (e.g. {@code rss}, {@code json}). */
    String format();

    /** HTTP Content-Type header value for the rendered output. */
    String contentType();

    /**
     * Renders the feed to a string.
     *
     * @param feed normalized feed to render
     * @return rendered output
     * @throws Exception when rendering fails
     */
    String render(Feed feed) throws Exception;
}
