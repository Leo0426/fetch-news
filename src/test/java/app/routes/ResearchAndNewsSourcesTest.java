package app.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.core.Feed;
import app.core.FetchClient;
import app.core.RouteContext;
import app.routes.github.ClaudeCodeReleasesRoute;
import app.routes.github.ComfyUiReleasesRoute;
import app.routes.github.LlamaCppReleasesRoute;
import app.routes.github.OpenWebUiReleasesRoute;
import app.routes.github.VllmReleasesRoute;
import app.routes.github.VscodeReleasesRoute;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ResearchAndNewsSourcesTest {
    private static final String RSS = """
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title>Published research and development</title>
                <link>https://source.example.org/</link>
                <description>Official updates</description>
                <item>
                  <title>A documented project</title>
                  <link>https://source.example.org/articles/project</link>
                  <description><![CDATA[<p>Detailed findings.</p>]]></description>
                  <pubDate>Wed, 21 Oct 2026 08:30:00 GMT</pubDate>
                  <dc:creator>Project team</dc:creator>
                  <category>Research</category>
                </item>
                <item>
                  <title>An undated update</title>
                  <link>https://source.example.org/articles/undated</link>
                </item>
              </channel>
            </rss>
            """;
    private static final String ATOM = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Published research and development</title>
              <link rel="self" href="https://source.example.org/atom.xml"/>
              <link rel="alternate" href="https://source.example.org/"/>
              <subtitle>Official updates</subtitle>
              <entry>
                <title>A documented project</title>
                <link rel="alternate" href="https://source.example.org/articles/project"/>
                <content type="html"><![CDATA[<p>Detailed findings.</p>]]></content>
                <published>2026-10-21T08:30:00Z</published>
                <author><name>Project team</name></author>
                <category term="Research"/>
              </entry>
              <entry>
                <title>An undated update</title>
                <link rel="alternate" href="https://source.example.org/articles/undated"/>
              </entry>
            </feed>
            """;

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void usesOfficialFeedOnMountedPathsAndPreservesPublishedContent(Source source) throws Exception {
        List<String> requests = new ArrayList<>();
        FetchClient client = url -> {
            requests.add(url);
            assertEquals(source.url(), url);
            return source.atom() ? ATOM : RSS;
        };
        RouteContext context = new RouteContext("/my/saved-source", Map.of(), Map.of(), null,
                "https://unrelated.example.org/feed.xml");

        Feed feed = source.create().apply(client).handle(context);

        assertEquals(List.of(source.url()), requests);
        assertEquals("Published research and development", feed.title());
        assertEquals("https://source.example.org/", feed.link());
        assertEquals(2, feed.items().size());
        var first = feed.items().getFirst();
        assertEquals("A documented project", first.title());
        assertEquals("https://source.example.org/articles/project", first.link());
        assertEquals("<p>Detailed findings.</p>", first.description());
        assertEquals(Instant.parse("2026-10-21T08:30:00Z"), first.pubDate());
        assertEquals("Project team", first.author());
        assertEquals(List.of("Research"), first.categories());
        assertEquals("https://source.example.org/articles/undated", feed.items().get(1).link());
        assertNull(feed.items().get(1).pubDate());
    }

    private static Stream<Source> sources() {
        return Stream.of(
                new Source("/deepmind/blog", "https://deepmind.google/blog/rss.xml", false,
                        client -> new DeepMindBlogRoute(client, null)),
                new Source("/bair/blog", "https://bair.berkeley.edu/blog/feed.xml", false,
                        client -> new BairBlogRoute(client, null)),
                new Source("/simonwillison/weblog", "https://simonwillison.net/atom/everything/", true,
                        client -> new SimonWillisonRoute(client, null)),
                new Source("/aihot/news", "https://aihot.news/feed.xml", false,
                        client -> new AiHotRoute(client, null)),
                new Source("/google/ai-blog", "https://blog.google/technology/ai/rss/", false,
                        client -> new GoogleAiBlogRoute(client, null)),
                new Source("/raschka/ahead-of-ai", "https://magazine.sebastianraschka.com/feed", false,
                        client -> new AheadOfAiRoute(client, null)),
                new Source("/karpathy/blog", "https://karpathy.bearblog.dev/feed/", false,
                        client -> new KarpathyBlogRoute(client, null)),
                new Source("/kexue/blog", "https://kexue.fm/feed", false,
                        client -> new KexueRoute(client, null)),
                new Source("/takara/daily-papers", "https://papers.takara.ai/api/feed", false,
                        client -> new TakaraDailyPapersRoute(client, null)),
                new Source("/pragmaticengineer/newsletter", "https://newsletter.pragmaticengineer.com/feed", false,
                        client -> new PragmaticEngineerRoute(client, null)),
                new Source("/github/product-news", "https://github.blog/news-insights/product-news/feed/", false,
                        client -> new GithubProductNewsRoute(client, null)),
                new Source("/vscode/releases", "https://github.com/microsoft/vscode/releases.atom", true,
                        client -> new VscodeReleasesRoute(client, null)),
                new Source("/open-webui/releases", "https://github.com/open-webui/open-webui/releases.atom", true,
                        client -> new OpenWebUiReleasesRoute(client, null)),
                new Source("/claude-code/releases", "https://github.com/anthropics/claude-code/releases.atom", true,
                        client -> new ClaudeCodeReleasesRoute(client, null)),
                new Source("/comfyui/releases", "https://github.com/Comfy-Org/ComfyUI/releases.atom", true,
                        client -> new ComfyUiReleasesRoute(client, null)),
                new Source("/llama-cpp/releases", "https://github.com/ggml-org/llama.cpp/releases.atom", true,
                        client -> new LlamaCppReleasesRoute(client, null)),
                new Source("/landian/news", "https://www.landiannews.com/feed", false,
                        client -> new LandianNewsRoute(client, null)),
                new Source("/juejin/posts", "https://juejin.cn/rss", false,
                        client -> new JuejinRoute(client, null)),
                new Source("/36kr/newsflashes", "https://www.36kr.com/feed-newsflash", false,
                        client -> new ThirtySixKrNewsflashRoute(client, null)),
                new Source("/nytimes/cn", "https://cn.nytimes.com/rss/", false,
                        client -> new NytChineseRoute(client, null)),
                new Source("/vllm/releases", "https://github.com/vllm-project/vllm/releases.atom", true,
                        client -> new VllmReleasesRoute(client, null)),
                new Source("/microsoft/research", "https://www.microsoft.com/en-us/research/feed/", false,
                        client -> new MicrosoftResearchRoute(client, null)),
                new Source("/nvidia/blog", "https://developer.nvidia.com/blog/feed", true,
                        client -> new NvidiaTechBlogRoute(client, null)),
                new Source("/importai/newsletter", "https://importai.substack.com/feed", false,
                        client -> new ImportAiRoute(client, null)),
                new Source("/latentspace/posts", "https://www.latent.space/feed", false,
                        client -> new LatentSpaceRoute(client, null)),
                new Source("/interconnects/posts", "https://www.interconnects.ai/feed", false,
                        client -> new InterconnectsRoute(client, null)),
                new Source("/oneusefulthing/posts", "https://www.oneusefulthing.org/feed", false,
                        client -> new OneUsefulThingRoute(client, null)),
                new Source("/lilianweng/blog", "https://lilianweng.github.io/index.xml", false,
                        client -> new LilianWengRoute(client, null)),
                new Source("/qbitai/news", "https://www.qbitai.com/feed", false,
                        client -> new QbitAiRoute(client, null)),
                new Source("/leiphone/news", "https://www.leiphone.com/feed", false,
                        client -> new LeiphoneRoute(client, null)),
                new Source("/github/engineering", "https://github.blog/engineering/feed/", false,
                        client -> new GithubEngineeringRoute(client, null)),
                new Source("/github/ai-ml", "https://github.blog/ai-and-ml/feed/", false,
                        client -> new GithubAiMlRoute(client, null)),
                new Source("/github/security", "https://github.blog/security/feed/", false,
                        client -> new GithubSecurityRoute(client, null)),
                new Source("/huxiu/articles", "https://rss.huxiu.com/", false,
                        client -> new HuxiuRoute(client, null)),
                new Source("/tmtpost/news", "https://www.tmtpost.com/rss.xml", false,
                        client -> new TmtpostRoute(client, null)),
                new Source("/ithome/news", "https://www.ithome.com/rss/", false,
                        client -> new IthomeRoute(client, null)),
                new Source("/zhidx/news", "https://www.zhidx.com/rss", false,
                        client -> new ZhidxRoute(client, null)),
                new Source("/appinn/posts", "https://www.appinn.com/feed/", false,
                        client -> new AppinnRoute(client, null)),
                new Source("/oschina/news", "https://www.oschina.net/news/rss", false,
                        client -> new OschinaNewsRoute(client, null)),
                new Source("/woshipm/articles", "https://www.woshipm.com/feed", false,
                        client -> new WoshipmRoute(client, null)),
                new Source("/npr/world", "https://feeds.npr.org/1004/rss.xml", false,
                        client -> new NprWorldRoute(client, null)),
                new Source("/lwn/headlines", "https://lwn.net/headlines/rss", false,
                        client -> new LwnHeadlinesRoute(client, null)),
                new Source("/ftchinese/news", "https://www.ftchinese.com/rss/news", false,
                        client -> new FtChineseRoute(client, null)),
                new Source("/arstechnica/latest", "https://feeds.arstechnica.com/arstechnica/index", false,
                        client -> new ArsTechnicaRoute(client, null)),
                new Source("/ifanr/latest", "https://www.ifanr.com/feed", false,
                        client -> new IfanrRoute(client, null)),
                new Source("/geekpark/news", "https://www.geekpark.net/rss", false,
                        client -> new GeekParkRoute(client, null)));
    }

    private record Source(String path, String url, boolean atom, Function<FetchClient, GenericRssRoute> create) {
        @Override
        public String toString() {
            return path;
        }
    }
}
