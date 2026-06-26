package app.reader;

import java.util.List;

public record BriefingSection(String category, List<ReaderItem> items, int totalCount) {
    public BriefingSection(String category, List<ReaderItem> items) {
        this(category, items, items.size());
    }
}
