package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.ai.AiConfig;
import app.ai.AiConfigStore;
import app.ai.ArticleSummarizer;
import app.core.CacheService;
import app.core.FeedItem;
import app.core.RouteConfigStore;
import app.core.RouteRegistry;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class AdminSummaryQueueTest {
    @TempDir
    Path directory;
    private FeedStore store;
    private AiConfigStore config;
    private ArticleSummarizer summarizer;
    private AdminResource admin;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        store = new FeedStore(directory.resolve("feed.db"), mapper);
        config = new AiConfigStore(directory.resolve("ai.json"), mapper);
        config.save(new AiConfig("http://127.0.0.1:1", "test", true));
        summarizer = new ArticleSummarizer(null, store, null, config);
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var runtime = new AppRuntime(new CacheService(), new RouteRegistry(List.of()),
                new RouteConfigStore(directory.resolve("routes.json"), mapper));
        var templates = new AdminTemplateRenderer(engine, store, runtime, new AppInfo());
        admin = new AdminResource(runtime, mapper, templates, store, config, null,
                summarizer, null, null, null);
    }

    @AfterEach
    void close() throws Exception {
        summarizer.close();
        store.close();
    }

    @Test
    void submitsStoredArticleWithoutUsingCallerTitleAndReturnsEncodedPollingFragment() throws Exception {
        String link = "https://example.com/a?word=中文+&quote=\"<script>";
        save(link);
        Document fragment = Jsoup.parse(admin.summarizeItem(link, "<script>caller title</script>"));
        assertEquals(1, fragment.select(".item-summary-gen").size());
        assertEquals("every 2s", fragment.select(".item-summary-gen").attr("hx-trigger"));
        assertEquals(link, pollLink(fragment));
        assertTrue(fragment.text().contains("排队中"));
        assertTrue(fragment.select("script").isEmpty());
        assertFalse(fragment.text().contains("caller title"));
        assertEquals("Stored title", store.loadItem(link).title());
        assertEquals("queued", summarizer.progress(link).state());
        assertNull(store.loadSummary(link));

        Document page = Jsoup.parse(admin.browseItems("/news", 0));
        assertEquals("every 2s", page.select(".item-summary-gen").attr("hx-trigger"));
        assertEquals(link, pollLink(page));
        assertFalse(page.select(".item-summary-gen").hasAttr("hx-post"));
    }

    @Test
    void statusReadsDoNotEnqueueAndDisabledAiKeepsQueuedAndCachedResultsReadable() throws Exception {
        String link = "https://example.com/a";
        save(link);
        Document idle = Jsoup.parse(admin.summaryStatus(link));
        assertTrue(idle.text().contains("生成摘要"));
        assertEquals("none", summarizer.progress(link).state());
        admin.summarizeItem(link, "ignored");
        config.save(new AiConfig("http://127.0.0.1:1", "test", false));
        Document paused = Jsoup.parse(admin.summaryStatus(link));
        assertTrue(paused.text().contains("任务保留在队列中"));
        assertEquals("every 2s", paused.select(".item-summary-gen").attr("hx-trigger"));
        assertTrue(Jsoup.parse(admin.summarizeItem(link, "ignored")).text().contains("排队中"));
        store.saveSummary(link, "<script>literal summary</script>", "test");
        Document completed = Jsoup.parse(admin.summarizeItem(link, "ignored"));
        assertEquals("<script>literal summary</script>", completed.select(".item-summary").text());
        assertFalse(completed.select(".item-summary-gen").hasAttr("hx-get"));
        assertTrue(completed.select("script").isEmpty());
    }

    @Test
    void unknownAndDisabledArticlesCannotBeEnqueued() throws Exception {
        Document unknown = Jsoup.parse(admin.summarizeItem("https://example.com/unknown", "ignored"));
        assertTrue(unknown.text().contains("未找到"));
        assertTrue(unknown.select("[hx-post]").isEmpty());
        assertTrue(Jsoup.parse(admin.summaryStatus("https://example.com/unknown")).text().contains("未找到"));
        String link = "https://example.com/a";
        save(link);
        config.save(new AiConfig("http://127.0.0.1:1", "test", false));
        assertTrue(Jsoup.parse(admin.summarizeItem(link, "ignored")).text().contains("AI 未启用"));
        assertEquals("none", summarizer.progress(link).state());
    }

    @Test
    void batchOnlyEnqueuesEligibleNewItemsAndNeverClaimsFailedItemsCompleted() throws Exception {
        String cached = "https://example.com/cached";
        String pending = "https://example.com/pending";
        String failed = "https://example.com/failed";
        String fresh = "https://example.com/fresh";
        for (String link : List.of(cached, pending, failed, fresh)) save(link);
        store.saveSummary(cached, "Existing summary", "test");
        summarizer.request(pending);
        store.markSummaryFinished(failed, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), "test", "private failure");
        assertTrue(Jsoup.parse(admin.summarizeBatch("/news")).text().contains("已加入队列 1 条"));
        assertEquals("queued", summarizer.progress(fresh).state());
        assertEquals("error", summarizer.progress(failed).state());
        String repeated = Jsoup.parse(admin.summarizeBatch("/news")).text();
        assertTrue(repeated.contains("没有新增任务"));
        assertFalse(repeated.contains("全部条目已有摘要"));
        assertEquals(1, Jsoup.parse(admin.summaryStatus(failed)).select("[hx-post]").size());
        Document initial = Jsoup.parse(admin.browseItems("/news", 0));
        assertFalse(initial.text().contains("private failure"));
        assertTrue(initial.text().contains("请检查 AI Provider 配置或文章内容后重试"));
    }

    @Test
    void interruptedTaskHasTheSameRecoverableMessageInInitialAndPolledViews() throws Exception {
        String link = "https://example.com/interrupted";
        save(link);
        store.markSummaryFinished(link, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), "test", "summary interrupted");
        assertTrue(Jsoup.parse(admin.browseItems("/news", 0)).text().contains("上次摘要任务已中断，请重试。"));
        Document polled = Jsoup.parse(admin.summaryStatus(link));
        assertTrue(polled.text().contains("上次摘要任务已中断，请重试。"));
        assertEquals(1, polled.select("[hx-post]").size());
    }

    @Test
    void storageFailureOffersStatusCheckInsteadOfAnotherGenerationRequest() throws Exception {
        var unavailable = new AdminResource(null, null, null, null, config, null,
                null, null, null, null);
        assertTrue(Jsoup.parse(unavailable.summarizeItem("x", "y")).text().contains("存储不可用"));
        assertTrue(Jsoup.parse(unavailable.summarizeBatch("/news")).text().contains("存储不可用"));
        String link = "https://example.com/a";
        save(link);
        store.close();
        Document failed = Jsoup.parse(admin.summaryStatus(link));
        assertTrue(failed.text().contains("重新查看状态"));
        assertNotNull(failed.selectFirst("button[hx-get]"));
        assertTrue(failed.select("[hx-post]").isEmpty());
        assertFalse(failed.select(".item-summary-gen").hasAttr("hx-trigger"));
        assertFalse(failed.text().contains("SQL"));
        assertFalse(failed.text().contains("feed.db"));
    }

    private void save(String link) throws Exception {
        store.saveItems("/news", List.of(new FeedItem("Stored title", link, "Stored body", Instant.EPOCH,
                null, List.of())));
    }

    private static String pollLink(Document document) {
        String url = document.select(".item-summary-gen").attr("hx-get");
        return URLDecoder.decode(url.substring(url.indexOf("?link=") + 6), StandardCharsets.UTF_8);
    }
}
