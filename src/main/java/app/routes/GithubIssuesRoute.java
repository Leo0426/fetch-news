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
 * Route that lists open GitHub issues for any public repository.
 *
 * <p>Path: /github/issues/:owner/:repo
 */
public class GithubIssuesRoute implements RouteHandler {
    private static final String API_URL =
            "https://api.github.com/repos/%s/%s/issues?state=open&per_page=30";

    private final FetchClient fetchClient;
    private final ObjectMapper objectMapper;

    public GithubIssuesRoute(FetchClient fetchClient, ObjectMapper objectMapper) {
        this.fetchClient = fetchClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Feed handle(RouteContext context) throws Exception {
        String owner = context.pathParam("owner");
        String repo  = context.pathParam("repo");
        if (owner == null || repo == null) {
            throw new RouteException(RouteError.INVALID_PARAMETER, "owner and repo are required");
        }
        String url = String.format(API_URL, owner, repo);
        String json = fetchClient.get(url);
        try {
            JsonNode issues = objectMapper.readTree(json);
            if (!issues.isArray()) {
                throw new RouteException(RouteError.UPSTREAM_INVALID_CONTENT, "GitHub API did not return an array");
            }
            List<FeedItem> items = new ArrayList<>();
            for (JsonNode issue : issues) {
                // skip pull requests which also appear in issues endpoint
                if (!issue.path("pull_request").isMissingNode()) continue;
                FeedItem item = toItem(issue);
                if (item != null) items.add(item);
            }
            if (items.isEmpty()) {
                throw new RouteException(RouteError.EMPTY_FEED);
            }
            String repoUrl = "https://github.com/" + owner + "/" + repo;
            return new Feed(
                    owner + "/" + repo + " issues",
                    repoUrl + "/issues",
                    "Open issues for " + owner + "/" + repo,
                    items);
        } catch (RouteException e) {
            throw e;
        } catch (Exception e) {
            throw new RouteException(RouteError.PARSER_FAILED, "parser failed: " + e.getMessage(), e);
        }
    }

    private FeedItem toItem(JsonNode issue) {
        String title   = issue.path("title").asText(null);
        String htmlUrl = issue.path("html_url").asText(null);
        if (title == null || htmlUrl == null) return null;

        String body      = issue.path("body").asText(null);
        String createdAt = issue.path("created_at").asText(null);
        String author    = issue.path("user").path("login").asText(null);
        List<String> labels = new ArrayList<>();
        for (JsonNode label : issue.path("labels")) {
            String name = label.path("name").asText(null);
            if (name != null) labels.add(name);
        }
        String desc = body != null && !body.isBlank()
                ? "<pre>" + escapeHtml(body.length() > 1000 ? body.substring(0, 1000) + "…" : body) + "</pre>"
                : null;

        return new FeedItem(title, htmlUrl, desc, DateParser.parseInstantOrNull(createdAt), author, labels);
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
