package app.reader;

import java.net.URI;
import java.util.Set;

public final class ArticleNavigation {
    private static final Set<String> RETURN_PATHS = Set.of("/reader", "/reader/search",
            "/reader/bookmarks", "/reader/briefing");
    private ArticleNavigation() {}

    public static String returnUrl(String value) {
        if (validCharacters(value)) {
            try {
                URI uri = URI.create(value);
                if (uri.getScheme() == null && uri.getRawAuthority() == null && RETURN_PATHS.contains(uri.getRawPath())) {
                    return value;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return "/reader";
    }

    public static String originalUrl(String value) {
        if (validCharacters(value)) {
            try {
                URI uri = URI.create(value);
                if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && !uri.getHost().isBlank()) return value;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private static boolean validCharacters(String value) {
        return value != null && !value.isBlank() && value.length() <= 8192 && !value.contains("\\")
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
