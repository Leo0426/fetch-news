package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import java.util.Set;

/**
 * Route that proxies BBC News RSS feeds.
 *
 * <p>Path: /bbc/news/:category  (e.g. world, technology, science_and_environment)
 */
public class BBCNewsRoute extends RssProxyRoute {

    private static final Set<String> KNOWN_CATEGORIES = Set.of(
            "world", "uk", "business", "politics", "health",
            "education", "science_and_environment", "technology", "entertainment_and_arts");

    public BBCNewsRoute(FetchClient fetchClient) {
        super(fetchClient);
    }

    @Override
    protected String feedUrl(RouteContext context) {
        String category = context.pathParam("category");
        if (category == null || category.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "category is required");
        }
        if (!KNOWN_CATEGORIES.contains(category)) {
            throw new RouteException(RouteError.INVALID_PARAMETER,
                    "unknown BBC category: " + category + ". Known: " + KNOWN_CATEGORIES);
        }
        return "https://feeds.bbci.co.uk/news/" + category + "/rss.xml";
    }
}
