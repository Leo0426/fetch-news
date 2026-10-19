package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BriefingSectionTest {
    @Test
    void foldsWidthCaseAndWhitespaceVariantsWithoutRewritingDisplayedTitles() {
        var first = item("/first", "ＦｅｔｃｈＮｅｗｓ　发布　２．０　版本");
        var second = item("/second", "fetchnews  发布\t2.0 版本");
        var section = BriefingSection.from("科技", List.of(first, second));

        assertEquals(1, section.stories().size());
        assertEquals("ＦｅｔｃｈＮｅｗｓ　发布　２．０　版本", section.stories().getFirst().primary().title());
        assertEquals(List.of(second), section.stories().getFirst().related());
        assertEquals(2, section.totalCount());
        assertEquals(1, section.totalStoryCount());
    }

    @Test
    void leavesShortGenericTitlesSeparate() {
        var section = BriefingSection.from("新闻", List.of(
                item("/first", "今日新闻"), item("/second", "今日新闻")));

        assertEquals(2, section.stories().size());
        assertEquals(2, section.totalStoryCount());
    }

    @Test
    void handlesUnicodeWhitespaceSeparatorsInMatchingTitles() {
        var section = BriefingSection.from("科技", List.of(
                item("/first", "FetchNews\u0085发布2.0正式版本"),
                item("/second", "fetchnews 发布2.0正式版本")));

        assertEquals(1, section.stories().size());
        assertEquals(1, section.stories().getFirst().related().size());
    }

    @Test
    void countsLettersAndDigitsRatherThanPunctuationForShortHeadlineProtection() {
        var section = BriefingSection.from("科技", List.of(
                item("/short-one", "abcdefghijk!!!"), item("/short-two", "abcdefghijk!!!"),
                item("/long-one", "abcdefghijkl"), item("/long-two", "abcdefghijkl")));

        assertEquals(3, section.stories().size());
        assertEquals(0, section.stories().getFirst().related().size());
        assertEquals(1, section.stories().getLast().related().size());
    }

    @Test
    void preservesNumbersPunctuationAndNewDevelopmentsAsSeparateStories() {
        var section = BriefingSection.from("科技", List.of(
                item("/one", "FetchNews 发布 2.0 正式版本"),
                item("/two", "FetchNews 发布 3.0 正式版本"),
                item("/three", "FetchNews 发布 2.0 正式版本后出现故障"),
                item("/four", "Using C++ for production news fetching"),
                item("/five", "Using C# for production news fetching")));

        assertEquals(5, section.stories().size());
        assertEquals(List.of("https://example.test/one", "https://example.test/two", "https://example.test/three",
                        "https://example.test/four", "https://example.test/five"),
                section.stories().stream().map(story -> story.primary().link()).toList());
    }

    @Test
    void appliesDisplayLimitAfterGroupingAndRetainsEveryMemberOfVisibleStories() {
        List<ReaderItem> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) items.add(item("/repeat-" + i, "我国成功发射新一代气象观测卫星"));
        for (int i = 0; i < 16; i++) items.add(item("/unique-" + i, "独立报道 " + i));

        var section = BriefingSection.from("新闻", items);

        assertEquals(36, section.totalCount());
        assertEquals(17, section.totalStoryCount());
        assertEquals(15, section.stories().size());
        assertEquals("https://example.test/repeat-0", section.stories().getFirst().primary().link());
        assertEquals(19, section.stories().getFirst().related().size());
        assertEquals("https://example.test/unique-13", section.stories().getLast().primary().link());
    }

    @Test
    void emptyInputHasNoStoriesOrArticles() {
        var section = BriefingSection.from("新闻", List.of());

        assertEquals(List.of(), section.stories());
        assertEquals(0, section.totalCount());
        assertEquals(0, section.totalStoryCount());
    }

    private ReaderItem item(String path, String title) {
        return new ReaderItem(path, title, "https://example.test" + path, null, null, null, null);
    }
}
