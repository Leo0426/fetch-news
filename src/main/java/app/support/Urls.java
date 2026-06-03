package app.support;

import java.net.URI;

/**
 * URL helpers used by route parsers.
 */
public final class Urls {
    /**
     * Prevents construction of this utility class.
     */
    private Urls() {}

    /**
     * Resolves a possibly relative href against a base URL.
     *
     * @param baseUrl source page URL
     * @param href link value from the source document
     * @return absolute URL string
     */
    public static String resolve(String baseUrl, String href) {
        return URI.create(baseUrl).resolve(href).toString();
    }
}
