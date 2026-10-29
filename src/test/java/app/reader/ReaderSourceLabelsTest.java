package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.TestPages;
import app.core.FeedItem;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.Topic;
import app.store.BookmarkedItem;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReaderSourceLabelsTest {
    @Test
    void recentReaderGroupsUseConfiguredMountNameAndRegisteredSourceCategory(@TempDir Path directory)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var runtime = TestPages.runtime(new RouteConfigStore(directory.resolve("routes.json"), mapper),
                new Route("/rss", context -> { throw new AssertionError("Reading must not fetch"); },
                        "RSS", Topic.GENERAL));
        var renderer = new ReaderTemplateRenderer(TestPages.engine(), runtime);
        String source = "/my-custom-reading";
        runtime.routeConfigStore().save(new RouteConfig(source, "/rss", true, 300, 1800,
                60, null, "https://example.test/feed.xml", "我的阅读"));
        try (FeedStore store = new FeedStore(directory.resolve("feed.db"), mapper)) {
            store.saveItems(source, List.of(new FeedItem("Article", "https://example.com/article", "Stored body",
                    Instant.EPOCH, null, List.of())));
            var reader = new ReaderResource(store, runtime, renderer, null, null);

            var page = Jsoup.parse(reader.reader(24));
            assertEquals("我的阅读", page.selectFirst(".rd-section-name").text());
            assertEquals("通用", page.selectFirst(".reader-source-tag").text());
            assertEquals("通用", page.selectFirst(".rd-section").attr("data-tag"));
        }
    }

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
                Arguments.of("/aihot/news", "AIHOT"),
                Arguments.of("/google/ai-blog", "Google AI"),
                Arguments.of("/raschka/ahead-of-ai", "Ahead of AI"),
                Arguments.of("/karpathy/blog", "Karpathy"),
                Arguments.of("/kexue/blog", "科学空间"),
                Arguments.of("/takara/daily-papers", "Takara 论文速递"),
                Arguments.of("/pragmaticengineer/newsletter", "Pragmatic Engineer"),
                Arguments.of("/github/product-news", "GitHub"),
                Arguments.of("/vscode/releases", "VS Code"),
                Arguments.of("/open-webui/releases", "Open WebUI"),
                Arguments.of("/claude-code/releases", "Claude Code"),
                Arguments.of("/comfyui/releases", "ComfyUI"),
                Arguments.of("/llama-cpp/releases", "llama.cpp"),
                Arguments.of("/landian/news", "蓝点网"),
                Arguments.of("/juejin/posts", "掘金"),
                Arguments.of("/36kr/newsflashes", "36氪"),
                Arguments.of("/nytimes/cn", "纽约时报中文网"),
                Arguments.of("/microsoft/research", "Microsoft Research"),
                Arguments.of("/nvidia/blog", "NVIDIA"),
                Arguments.of("/importai/newsletter", "Import AI"),
                Arguments.of("/latentspace/posts", "Latent Space"),
                Arguments.of("/interconnects/posts", "Interconnects"),
                Arguments.of("/oneusefulthing/posts", "One Useful Thing"),
                Arguments.of("/lilianweng/blog", "Lil'Log"),
                Arguments.of("/qbitai/news", "量子位"),
                Arguments.of("/leiphone/news", "雷峰网"),
                Arguments.of("/github/engineering", "GitHub"),
                Arguments.of("/github/ai-ml", "GitHub"),
                Arguments.of("/github/security", "GitHub"),
                Arguments.of("/huxiu/articles", "虎嗅"),
                Arguments.of("/tmtpost/news", "钛媒体"),
                Arguments.of("/ithome/news", "IT之家"),
                Arguments.of("/zhidx/news", "智东西"),
                Arguments.of("/appinn/posts", "小众软件"),
                Arguments.of("/oschina/news", "开源中国"),
                Arguments.of("/woshipm/articles", "人人都是产品经理"),
                Arguments.of("/vllm/releases", "vLLM"),
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
