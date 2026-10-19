package app.reader;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public record BriefingSection(String category, List<BriefingStory> stories, int totalCount, int totalStoryCount) {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public BriefingSection {
        stories = List.copyOf(stories);
    }

    public static BriefingSection from(String category, List<ReaderItem> items) {
        Map<String, List<ReaderItem>> grouped = new LinkedHashMap<>();
        for (ReaderItem item : items) {
            grouped.computeIfAbsent(foldKey(item), ignored -> new ArrayList<>()).add(item);
        }
        List<BriefingStory> stories = grouped.values().stream()
                .map(group -> new BriefingStory(group.getFirst(), group.subList(1, group.size())))
                .toList();
        return new BriefingSection(category, stories.stream().limit(15).toList(), items.size(), stories.size());
    }

    private static String foldKey(ReaderItem item) {
        String title = WHITESPACE.matcher(Normalizer.normalize(item.title(), Normalizer.Form.NFKC))
                .replaceAll(" ").strip().toLowerCase(Locale.ROOT);
        // Short recurring headlines are too ambiguous to treat as repeat coverage.
        return title.codePoints().filter(Character::isLetterOrDigit).count() >= 12
                ? "title:" + title : "link:" + item.link();
    }
}
