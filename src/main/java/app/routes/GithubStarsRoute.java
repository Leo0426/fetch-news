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
 * Route that lists recently starred GitHub repositories for a user.
 *
 * <p>Path: /github/stars/:user
 */
public class GithubStarsRoute implements RouteHandler {
    private static final String API_URL =
            "https://api.github.com/users/%s/starred?per_page=30&sort=created&direction=desc";

    private final FetchClient fetchClient;
    private final ObjectMapper objectMapper;

    public GithubStarsRoute(FetchClient fetchClient, ObjectMapper objectMapper) {
        this.fetchClient = fetchClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String user = context.pathParam("user");
        if (user == null || user.isBlank()) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "user is required");
        }
        String url = String.format(API_URL, user);
        String json = fetchClient.get(url);
        try {
            JsonNode repos = objectMapper.readTree(json);
            if (!repos.isArray()) {
                throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT, "GitHub API did not return an array");
            }
            List<FeedItem> items = new ArrayList<>();
            for (JsonNode repo : repos) {
                FeedItem item = toItem(repo);
                if (item != null) items.add(item);
            }
            if (items.isEmpty()) {
                throw new RouteException(RouteError.EMPTY_FEED);
            }
            return new Feed(
                    user + "'s starred repositories",
                    "https://github.com/" + user + "?tab=stars",
                    "Repositories starred by " + user,
                    items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    private FeedItem toItem(JsonNode repo) {
        String fullName = repo.path("full_name").asText(null);
        String htmlUrl  = repo.path("html_url").asText(null);
        if (fullName == null || htmlUrl == null) return null;

        String desc     = repo.path("description").asText(null);
        String pushedAt = repo.path("pushed_at").asText(null);
        String language = repo.path("language").asText(null);
        int stars       = repo.path("stargazers_count").asInt(0);

        String title = fullName + (language != null ? "  [" + language + "]" : "");
        String body  = "<p>⭐ " + stars + (desc != null ? " — " + escapeHtml(desc) : "") + "</p>";

        List<String> cats = language != null ? List.of(language) : List.of();
        return new FeedItem(title, htmlUrl, body, DateParser.parseInstantOrNull(pushedAt), null, cats);
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
