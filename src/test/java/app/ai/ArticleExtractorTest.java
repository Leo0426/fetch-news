package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArticleExtractorTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"article", "main", "div role='main'", "div itemprop='articleBody'", "div class='article-body'",
            "div class='post-content'", "div class='entry-content'", "div class='article__body'",
            "div class='story-body'", "div class='content-body'"})
    void semanticArticleKeepsCleanOriginalLanguageWithoutGenerationAndReusesCache(String container) throws Exception {
        var mapper = new ObjectMapper();
        var config = new AiConfigStore(directory.resolve("ai.json"), mapper);
        var model = new OllamaClient(config, mapper) {
            @Override public Generation generateWithMetadata(String prompt) {
                throw new AssertionError("A clear article body must not need model extraction");
            }
        };
        String original = "保留原文语言。Original reporting stays unchanged. ".repeat(10).strip();
        var fetches = new AtomicInteger();
        Path database = directory.resolve("extract.db");
        try (var store = new FeedStore(database, mapper)) {
            var extractor = new ArticleExtractor(model, store, url -> {
                fetches.incrementAndGet();
                return "<body>Outside article text<" + container + "><nav>Navigation</nav><p>" + original
                        + "</p><script>tracking()</script><aside>Related links</aside><div class='ad'>Advert</div></"
                        + container.split(" ")[0] + "></body>";
            });
            assertEquals(original, extractor.extract(item()));
            assertEquals(original, extractor.extract(item()));
            assertEquals(original, store.aiCache().loadExtract(item().link()));
            assertEquals(1, fetches.get());
            assertEquals("html", extractionModel(database));
        }
    }

    @Test
    void semanticCacheIsBoundedWithoutSplittingSupplementaryCharacters() throws Exception {
        var mapper = new ObjectMapper();
        String original = "𠀀".repeat(4001);
        try (var store = new FeedStore(directory.resolve("unicode.db"), mapper)) {
            var extractor = new ArticleExtractor(null, store, url -> "<article>" + original + "</article>");
            String content = extractor.extract(item());
            assertEquals("𠀀".repeat(4000), content);
            assertEquals(content, store.aiCache().loadExtract(item().link()));
        }
    }

    @Test
    void innerArticleBodyExcludesOuterAuthorAndActionsAndKeepsParagraphs() throws Exception {
        var mapper = new ObjectMapper();
        String first = "第一段的正文。".repeat(25);
        String second = "Second paragraph keeps its original language. ".repeat(8).strip();
        try (var store = new FeedStore(directory.resolve("nested.db"), mapper)) {
            var extractor = new ArticleExtractor(null, store, url -> "<article><div>重复作者 关注 分享 举报</div>"
                    + "<div class='article-body'><p>" + first + "</p><button>分享按钮</button>"
                    + "<span role='button'>举报按钮</span><div role='navigation'>相关推荐</div>"
                    + "<p>" + second + "</p></div><div>作者底栏</div></article>");

            String text = extractor.extract(item());
            assertEquals(first + "\n\n" + second, text);
            assertEquals(text, store.aiCache().loadExtract(item().link()));
        }
    }

    @Test
    void broadArticleFallbackRetainsBlockAndLineBreaksWithoutSplittingInlineText() throws Exception {
        var mapper = new ObjectMapper();
        String paragraph = "原文正文。".repeat(50);
        try (var store = new FeedStore(directory.resolve("structure.db"), mapper)) {
            var extractor = new ArticleExtractor(null, store, url -> "<article><h2>标题</h2><p>"
                    + paragraph + "<strong>行内强调</strong>继续正文<br>下一行</p><ul><li>条目一</li><li>条目二</li></ul></article>");

            assertEquals("标题\n\n" + paragraph + "行内强调继续正文\n下一行\n\n条目一\n\n条目二",
                    extractor.extract(item()));
        }
    }

    @Test
    void wholePageFallbackStillUsesModelCleaningAndCachesItsResult() throws Exception {
        var mapper = new ObjectMapper();
        var config = new AiConfigStore(directory.resolve("ai.json"), mapper);
        var prompts = new ArrayList<String>();
        var model = new OllamaClient(config, mapper) {
            @Override public Generation generateWithMetadata(String prompt) {
                prompts.add(prompt);
                return new Generation("模型清理后的正文", "extract-model");
            }
        };
        Path database = directory.resolve("fallback.db");
        try (var store = new FeedStore(database, mapper)) {
            var extractor = new ArticleExtractor(model, store,
                    url -> "<body><nav>Navigation</nav><article>短正文</article><div>"
                            + "整页正文。".repeat(60) + "</div></body>");
            assertEquals("模型清理后的正文", extractor.extract(item()));
            assertEquals("模型清理后的正文", extractor.extract(item()));
            assertEquals(1, prompts.size());
            assertTrue(prompts.getFirst().contains("整页正文"));
            assertFalse(prompts.getFirst().contains("Navigation"));
            assertEquals("extract-model", extractionModel(database));
        }
    }

    @Test
    void interruptedFetchDoesNotCacheSemanticContentOrCallTheModel() throws Exception {
        var mapper = new ObjectMapper();
        try (var store = new FeedStore(directory.resolve("interrupted.db"), mapper)) {
            var extractor = new ArticleExtractor(null, store, url -> {
                Thread.currentThread().interrupt();
                return "<article>" + "正文。".repeat(100) + "</article>";
            });
            try {
                assertThrows(InterruptedException.class, () -> extractor.extract(item()));
                assertNull(store.aiCache().loadExtract(item().link()));
            } finally { Thread.interrupted(); }
        }
        assertThrows(IllegalStateException.class, () -> new ArticleExtractor(null, null,
                url -> { throw new AssertionError("Unavailable storage must not fetch"); }).extract(item()));
    }

    @Test
    void summaryWorkerNeedsOnlyOneGenerationForShortFeedsWithSemanticArticleHtml() throws Exception {
        var mapper = new ObjectMapper();
        var config = new AiConfigStore(directory.resolve("summary-ai.json"), mapper);
        config.save(new AiConfig("http://127.0.0.1:1", "summary-model", true));
        var prompts = new ArrayList<String>();
        var model = new OllamaClient(config, mapper) {
            @Override public Generation generateWithMetadata(String prompt) {
                prompts.add(prompt);
                return new Generation("基于原文的摘要", "summary-model");
            }
        };
        String original = "原文的核心事实。".repeat(60);
        try (var store = new FeedStore(directory.resolve("summary.db"), mapper);
             var service = new ArticleSummarizer(model, store,
                     new ArticleExtractor(model, store, url -> "<article>" + original + "</article>"), config)) {
            store.saveItems("/source", List.of(item()));
            service.request(item().link());
            service.processNext();
            assertEquals("success", service.progress(item().link()).state());
            assertEquals("基于原文的摘要", store.aiCache().loadSummary(item().link()));
            assertEquals(original, store.aiCache().loadExtract(item().link()));
            assertEquals(1, prompts.size());
            assertTrue(prompts.getFirst().contains("2-3 句摘要"));
            assertTrue(prompts.getFirst().contains(original));
        }
    }

    private static FeedItem item() {
        return new FeedItem("Saved title", "https://example.com/article", "Short description", null, null, List.of());
    }

    private static String extractionModel(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var query = connection.prepareStatement("SELECT model FROM ai_extracts WHERE item_link=?")) {
            query.setString(1, item().link());
            try (var result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }
}
