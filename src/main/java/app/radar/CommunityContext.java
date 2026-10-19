package app.radar;

import java.util.List;

public record CommunityContext(String link, String discussionUrl, String title, String background,
                               List<Comment> comments, List<String> references, long fetchedAt, int failedComments) {
    public CommunityContext { comments = List.copyOf(comments); references = List.copyOf(references); }
    public record Comment(String author, String text, String url) {}
}
