package app.routes;

import app.core.Feed;
import app.core.FeedItem;
import app.core.FetchClient;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import app.support.DateParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Route that fetches GitHub releases for a repository via the public API.
 *
 * <p>Path: /github/releases/:owner/:repo
 */
public class GithubReleaseRoute implements RouteHandler {
    private static final String API_BASE = "https://api.github.com/repos";

    private final FetchClient fetchClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates the GitHub releases route with explicit fetch and JSON dependencies.
     *
     * @param fetchClient client used to call the GitHub API
     * @param objectMapper mapper used to parse the JSON response
     */
    public GithubReleaseRoute(FetchClient fetchClient, ObjectMapper objectMapper) {
        this.fetchClient = fetchClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String owner = context.pathParam("owner");
        String repo = context.pathParam("repo");
        if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "owner and repo are required");
        }
        String url = API_BASE + "/" + owner + "/" + repo + "/releases";
        String json = fetchClient.get(url);
        try {
            JsonNode releases = objectMapper.readTree(json);
            if (!releases.isArray()) {
                throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT, "GitHub API did not return an array");
            }
            List<FeedItem> items = new ArrayList<>();
            for (JsonNode release : releases) {
                if (release.path("draft").asBoolean(false)) {
                    continue;
                }
                FeedItem item = toItem(release);
                if (item.title() != null && item.link() != null) {
                    items.add(item);
                }
            }
            if (items.isEmpty()) {
                throw new RouteException(RouteError.EMPTY_FEED);
            }
            String repoUrl = "https://github.com/" + owner + "/" + repo;
            return new Feed(
                    owner + "/" + repo + " releases",
                    repoUrl + "/releases",
                    "GitHub releases for " + owner + "/" + repo,
                    items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    private FeedItem toItem(JsonNode release) {
        String tagName = release.path("tag_name").asText(null);
        String name = release.path("name").asText(null);
        String body = release.path("body").asText(null);
        String htmlUrl = release.path("html_url").asText(null);
        String publishedAt = release.path("published_at").asText(null);
        String author = release.path("author").path("login").asText(null);
        boolean prerelease = release.path("prerelease").asBoolean(false);

        String title = (name != null && !name.isBlank()) ? name : tagName;
        if (prerelease && title != null) {
            title = title + " (pre-release)";
        }
        String description = (body != null && !body.isBlank())
                ? "<pre>" + escapeHtml(body) + "</pre>"
                : null;

        return new FeedItem(
                title,
                htmlUrl,
                description,
                DateParser.parseInstantOrNull(publishedAt),
                author,
                List.of());
    }

    private String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
