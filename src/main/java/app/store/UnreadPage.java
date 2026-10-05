package app.store;

import java.util.List;

public record UnreadPage(
        List<StoredItem> items,
        List<String> bookmarkedLinks,
        long totalCount,
        Long nextCursor,
        List<String> sources) {

    public UnreadPage {
        items = List.copyOf(items);
        bookmarkedLinks = List.copyOf(bookmarkedLinks);
        sources = List.copyOf(sources);
    }
}
