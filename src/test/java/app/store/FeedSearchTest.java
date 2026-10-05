package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedSearchTest {
    @TempDir
    Path tempDir;

    @Test
    void findsTwoCharacterChineseTermInsideContinuousTitle() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("search.db"), new ObjectMapper())) {
            store.saveItems("/articles", List.of(item("本地模型部署指南", "model", "部署笔记")));

            assertEquals(List.of("https://example.com/model"),
                    store.search("模型", 10).stream().map(StoredItem::link).toList());
        }
    }

    private static FeedItem item(String title, String slug, String description) {
        return new FeedItem(title, "https://example.com/" + slug, description, null, null, List.of());
    }

    @Test
    void unicodeWhitespaceWithoutKeywordsDoesNotReturnEveryArticle() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("search.db"), new ObjectMapper())) {
            store.saveItems("/articles", List.of(item("本地模型部署指南", "model", "部署笔记")));
            assertTrue(store.search("\u00a0\u2003", 10).isEmpty());
        }
    }

    @Test
    void matchesAllKeywordsAcrossFieldsAndPrefersTitleMatches() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("search.db"), new ObjectMapper())) {
            store.saveItems("/articles", List.of(
                    item("本地模型指南", "title", "使用 Ollama 部署服务"),
                    item("Inference notes", "summary", "Ollama 部署"),
                    item("本地模型入门", "missing-word", "其他工具")));
            store.saveSummary("https://example.com/summary", "用本地模型保护隐私", "test");
            assertEquals(List.of("https://example.com/title", "https://example.com/summary"),
                    store.search("模型\u3000Ollama", 10).stream().map(StoredItem::link).toList());
            assertEquals("使用 Ollama 部署服务", store.searchHits("模型 Ollama", 10).getFirst().description());
        }
    }

    @Test
    void frozenSyntheticSearchCorpus() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode corpus;
        try (InputStream input = FeedSearchTest.class.getResourceAsStream("/search-scenarios.json")) {
            corpus = mapper.readTree(input);
        }
        try (FeedStore store = new FeedStore(tempDir.resolve("search.db"), mapper)) {
            for (var article : corpus.get("articles")) {
                store.saveItems("/articles", List.of(new FeedItem(article.get("title").asText(),
                        article.get("link").asText(), article.get("description").asText(), null, null, List.of())));
                store.saveSummary(article.get("link").asText(), article.get("summary").asText(), "fixture");
            }
            for (var scenario : corpus.get("queries")) {
                String query = scenario.get("query").asText();
                Set<String> expected = new java.util.HashSet<>();
                for (var id : scenario.get("expectedIds")) expected.add("https://fixture.invalid/" + id.asText());
                assertEquals(expected, store.search(query, 10).stream().map(StoredItem::link).collect(Collectors.toSet()),
                        scenario.get("scenario").asText() + ": " + query);
            }
        }
    }

    @Test
    void updatesAndRestartKeepSearchCurrentAndPreserveUrlIdentity() throws Exception {
        Path database = tempDir.resolve("search.db");
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.saveItems("/first", List.of(item("旧的模型说明", "model", "obsolete marker")));
            store.saveSummary("https://example.com/model", "关于显卡的旧摘要", "test");
            assertEquals(1, store.search("显卡", 10).size());
            store.saveItems("/second", List.of(item("新模型实践", "model", "current marker")));
            store.saveSummary("https://example.com/model", "关于推理的新摘要", "test");
            store.markRead("https://example.com/model");
            assertTrue(store.search("显卡", 10).isEmpty());
            assertTrue(store.search("obsolete", 10).isEmpty());
            assertEquals(1, store.search("模型 推理", 10).size());
            assertEquals("/first", store.search("current", 10).getFirst().routePath());
        }
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertEquals(List.of("https://example.com/model"),
                    store.search("模型 推理", 10).stream().map(StoredItem::link).toList());
        }
    }

    @Test
    void supplementaryHanTermsAreFoundWithoutChangingEnglishWordBoundaries() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("search.db"), new ObjectMapper())) {
            store.saveItems("/articles", List.of(
                    item("文献𠀀𠀁版本", "han", "Notes"),
                    item("JavaScript", "script", "Browser scripting"),
                    item("Java", "java", "Virtual threads")));
            assertEquals(List.of("https://example.com/han"),
                    store.search("𠀀𠀁", 10).stream().map(StoredItem::link).toList());
            assertEquals(List.of("https://example.com/java"),
                    store.search("Java", 10).stream().map(StoredItem::link).toList());
        }
    }

    @Test
    void doesNotReturnUnusableBlankArticleTitles() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("search.db"), new ObjectMapper())) {
            store.saveItems("/articles", List.of(item("\t\n", "empty-title", "关于模型")));
            assertTrue(store.search("模型", 10).isEmpty());
        }
    }
}
