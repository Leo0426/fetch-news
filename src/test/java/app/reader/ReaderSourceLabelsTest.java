package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.store.BookmarkedItem;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReaderSourceLabelsTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource({"newSources", "existingSources"})
    void usesTheSameSourceNameInReaderAndBookmarks(String path, String expected) {
        var item = new ReaderItem(path, "Article", "https://publisher.example/article",
                null, null, null, null);
        var bookmark = new BookmarkedItem("https://publisher.example/article", "Article", path, null);

        assertEquals(expected, item.sourceLabel(), "reader source label");
        assertEquals(expected, bookmark.sourceLabel(), "bookmark source label");
    }

    private static Stream<Arguments> newSources() {
        return Stream.of(
                Arguments.of("/ourworldindata/articles", "Our World in Data"),
                Arguments.of("/pewresearch/publications", "Pew Research Center"),
                Arguments.of("/hackaday/posts", "Hackaday"),
                Arguments.of("/raspberrypi/news", "Raspberry Pi"),
                Arguments.of("/godot/news", "Godot Engine"),
                Arguments.of("/factorio/blog", "Factorio Friday Facts"),
                Arguments.of("/jstor/daily", "JSTOR Daily"),
                Arguments.of("/public-domain-review/latest", "公共领域评论"),
                Arguments.of("/colossal/latest", "Colossal"),
                Arguments.of("/aperture/articles", "Aperture"),
                Arguments.of("/dezeen/latest", "Dezeen"),
                Arguments.of("/archdaily/latest", "ArchDaily"),
                Arguments.of("/gcores/articles", "机核"),
                Arguments.of("/yystv/articles", "游研社"),
                Arguments.of("/kff/health-news", "KFF Health News"),
                Arguments.of("/stat/news", "STAT"),
                Arguments.of("/psyche/magazine", "Psyche"),
                Arguments.of("/aps/research", "APS 心理科学研究"),
                Arguments.of("/edsurge/news", "EdSurge"),
                Arguments.of("/hechinger/report", "The Hechinger Report"),
                Arguments.of("/deepmind/blog", "DeepMind"),
                Arguments.of("/bair/blog", "BAIR"),
                Arguments.of("/simonwillison/weblog", "Simon Willison"),
                Arguments.of("/npr/world", "NPR"),
                Arguments.of("/lwn/headlines", "LWN"),
                Arguments.of("/ftchinese/news", "FT 中文网"),
                Arguments.of("/arstechnica/latest", "Ars Technica"),
                Arguments.of("/ifanr/latest", "爱范儿"),
                Arguments.of("/geekpark/news", "极客公园"));
    }

    private static Stream<Arguments> existingSources() {
        return Stream.of(
                Arguments.of(null, "?"),
                Arguments.of("", ""),
                Arguments.of("/", "/"),
                Arguments.of("plain", "plain"),
                Arguments.of("/custom", "custom"),
                Arguments.of("/custom/feed", "custom"),
                Arguments.of("/hn/top", "HN"),
                Arguments.of("/bbc/news/technology", "BBC"),
                Arguments.of("/github/changelog", "GitHub"),
                Arguments.of("/google/security", "Google Security"),
                Arguments.of("/google/security/latest", "Google Security"),
                Arguments.of("/google/research", "Google Research"),
                Arguments.of("/google/other", "Google Research"),
                Arguments.of("/google", "Google Research"));
    }
}
