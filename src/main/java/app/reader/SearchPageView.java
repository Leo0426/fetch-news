package app.reader;

import java.util.List;

public record SearchPageView(String query, List<SearchResultView> items, boolean hasMore, String error) {
    public SearchPageView {
        items = List.copyOf(items);
    }
}
