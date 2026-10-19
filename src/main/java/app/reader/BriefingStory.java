package app.reader;

import java.util.List;

public record BriefingStory(ReaderItem primary, List<ReaderItem> related) {
    public BriefingStory {
        related = List.copyOf(related);
    }
}
