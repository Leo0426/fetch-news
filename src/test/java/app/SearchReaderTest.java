package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.CacheService;
import app.core.FeedItem;
import app.core.RouteConfigStore;
import app.core.RouteRegistry;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class SearchReaderTest {
    @TempDir
    Path directory;
    private FeedStore store;
    private AdminTemplateRenderer renderer;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        store = new FeedStore(directory.resolve("feed.db"), mapper);
        AppRuntime runtime = new AppRuntime(new CacheService(), new RouteRegistry(List.of()),
                new RouteConfigStore(directory.resolve("routes.json"), mapper));
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        renderer = new AdminTemplateRenderer(engine, store, runtime, new AppInfo());
    }

    @AfterEach
    void close() throws Exception {
        store.close();
    }

    @Test
    void chineseResultHighlightsTitleAndEscapesAllSourceText() throws Exception {
        store.saveItems("/articles", List.of(new FeedItem("模型 <img src=x onerror=alert(1)>",
                "https://example.com/model", "<p>阅读模型资料 &lt;img onerror=alert(2)&gt;</p>", null, null, List.of())));
        var response = reader(store).search("模型");
        var page = Jsoup.parse(response.getBody());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, page.select(".search-result").size());
        assertEquals("模型", page.select(".search-result-heading mark").text());
        assertTrue(page.select(".search-excerpt").text().contains("正文摘录"));
        assertTrue(page.select(".search-result-heading").text().contains("<img"));
        assertTrue(page.select(".search-result img, .search-result script").isEmpty());
        assertEquals("模型", page.select("#history-query").val());
    }

    @Test
    void hundredResultsAreOnlyCalledTruncatedWhenAnotherMatchExists() throws Exception {
        store.saveItems("/articles", IntStream.range(0, 100).mapToObj(i ->
                new FeedItem("知识模型 " + i, "https://example.com/" + i, "记录", null, null, List.of())).toList());
        var exact = Jsoup.parse(reader(store).search("模型").getBody());
        assertEquals(100, exact.select(".search-result").size());
        assertTrue(exact.select(".search-limit-note").isEmpty());

        store.saveItems("/articles", List.of(new FeedItem("知识模型最后一篇", "https://example.com/last",
                "记录", null, null, List.of())));
        var truncated = Jsoup.parse(reader(store).search("模型").getBody());
        assertEquals(100, truncated.select(".search-result").size());
        assertTrue(truncated.select(".search-result-meta").text().contains("展示前 100"));
        assertFalse(truncated.select(".search-limit-note").isEmpty());
    }

    @Test
    void invalidInputAndStorageFailureNeverImpersonateNoResults() throws Exception {
        for (String query : List.of("字", "𠀀", "字".repeat(201))) {
            var response = reader(store).search(query);
            assertEquals(400, response.getStatusCode().value());
            assertFalse(Jsoup.parse(response.getBody()).select("[role=alert]").isEmpty());
        }
        assertEquals(503, reader(null).search("模型").getStatusCode().value());
        store.close();
        var failed = reader(store).search("模型");
        assertEquals(500, failed.getStatusCode().value());
        assertTrue(Jsoup.parse(failed.getBody()).select(".search-result-meta").isEmpty());
    }

    @Test
    void blankQueryShowsGuidanceAndMissingQueryShowsEmptyResults() {
        var initial = Jsoup.parse(reader(store).search("\u00a0\u2003").getBody());
        assertFalse(initial.select(".search-welcome").isEmpty());
        assertTrue(initial.select(".search-result-meta").isEmpty());
        var noResults = reader(store).search("不存在");
        assertEquals(200, noResults.getStatusCode().value());
        assertTrue(Jsoup.parse(noResults.getBody()).select(".search-result-meta").text().contains("暂无匹配"));
    }

    private ReaderResource reader(FeedStore feedStore) {
        return new ReaderResource(feedStore, null, renderer, null, null);
    }
}
