package app.reader;

import java.util.List;

public record UnreadPageView(
        List<ReaderItem> items,
        List<String> bookmarkedLinks,
        List<String> sources,
        String source,
        String query,
        boolean bookmarkedOnly,
        long totalCount,
        String nextUrl,
        String startUrl,
        boolean continued,
        boolean aiEnabled,
        String error) {
    public UnreadPageView {
        items = List.copyOf(items);
        bookmarkedLinks = List.copyOf(bookmarkedLinks);
        sources = List.copyOf(sources);
    }
}
