package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SearchResultViewTest {
    @Test
    void highlightsChineseTitleAndShowsMatchingBodyWithoutHtml() {
        ReaderItem article = new ReaderItem("/articles", "本地模型部署指南", "https://example.com/model",
                null, Instant.EPOCH, null, null);
        SearchResultView result = SearchResultView.from(article,
                "<p>使用<strong>模型</strong>完成本地推理。</p><script>alert('bad')</script>", "模型");

        assertEquals("模型", result.titleParts().stream().filter(SearchResultView.TextPart::matched)
                .map(SearchResultView.TextPart::text).collect(Collectors.joining()));
        assertEquals("正文摘录", result.excerptLabel());
        String excerpt = result.excerptParts().stream().map(SearchResultView.TextPart::text)
                .collect(Collectors.joining());
        assertTrue(excerpt.contains("模型"));
        assertFalse(excerpt.contains("<"));
        assertFalse(excerpt.contains("alert"));
    }

    @Test
    void longKeywordStillProducesBoundedExcerptWithoutSplittingEmoji() {
        ReaderItem article = new ReaderItem("/articles", "长文", "https://example.com/long",
                null, Instant.EPOCH, null, null);
        String keyword = "模".repeat(180);
        SearchResultView result = SearchResultView.from(article, "📚".repeat(100) + keyword + "📚".repeat(100), keyword);
        String excerpt = result.excerptParts().stream().map(SearchResultView.TextPart::text).collect(Collectors.joining());
        assertTrue(excerpt.codePointCount(0, excerpt.length()) <= 142);
        assertTrue(result.excerptParts().stream().anyMatch(SearchResultView.TextPart::matched));
        assertFalse(excerpt.codePoints().anyMatch(character -> character >= 0xD800 && character <= 0xDFFF));
    }

    @Test
    void showsSummaryWhenOnlySummaryMatchesAndTreatsSymbolsLiterally() {
        ReaderItem article = new ReaderItem("/articles", "Runtime update", "https://example.com/runtime",
                null, Instant.EPOCH, "使用 C++ 与 Node.js 构建本地模型", null);
        SearchResultView result = SearchResultView.from(article, "普通发布说明", "C++\u00a0模型");
        assertEquals("AI摘要", result.excerptLabel());
        assertEquals("C++模型", result.excerptParts().stream().filter(SearchResultView.TextPart::matched)
                .map(SearchResultView.TextPart::text).collect(Collectors.joining()));
    }

    @Test
    void foldedEnglishWordMatchSelectsItsActualSummaryContext() {
        ReaderItem article = new ReaderItem("/articles", "Reading notes", "https://example.com/accent",
                null, Instant.EPOCH, "CAFÉ résumé", null);
        SearchResultView result = SearchResultView.from(article, "Unrelated body content", "cafe");
        assertEquals("AI摘要", result.excerptLabel());
        assertEquals("CAFÉ", result.excerptParts().stream().filter(SearchResultView.TextPart::matched)
                .map(SearchResultView.TextPart::text).collect(Collectors.joining()));
    }

    @Test
    void wordHighlightsKeepBoundariesAndLiteralTermsKeepAccents() {
        ReaderItem article = new ReaderItem("/articles", "JavaScript news", "https://example.com/words",
                null, Instant.EPOCH, "Java and Cafe\u0301", null);
        SearchResultView result = SearchResultView.from(article, "JavaScript release", "Java cafe");
        assertTrue(result.titleParts().stream().noneMatch(SearchResultView.TextPart::matched));
        assertEquals("AI摘要", result.excerptLabel());
        assertEquals("JavaCafe\u0301", result.excerptParts().stream().filter(SearchResultView.TextPart::matched)
                .map(SearchResultView.TextPart::text).collect(Collectors.joining()));
        SearchResultView literal = SearchResultView.from(article, "café.js runtime", "cafe.js");
        assertTrue(literal.excerptParts().stream().noneMatch(SearchResultView.TextPart::matched));
    }
}
