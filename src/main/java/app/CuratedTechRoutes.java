package app;

import app.core.CacheService;
import app.core.CredentialConfigStore;
import app.core.FetchClient;
import app.core.Route;
import app.core.Topic;
import app.routes.AheadOfAiRoute;
import app.routes.AiHotRoute;
import app.routes.AppinnRoute;
import app.routes.ArsTechnicaRoute;
import app.routes.BairBlogRoute;
import app.routes.CloudflareBlogRoute;
import app.routes.DeepMindBlogRoute;
import app.routes.GeekParkRoute;
import app.routes.GithubAiMlRoute;
import app.routes.GithubChangelogRoute;
import app.routes.GithubEngineeringRoute;
import app.routes.GithubOpenSourceRoute;
import app.routes.GithubProductNewsRoute;
import app.routes.GithubSecurityRoute;
import app.routes.GoogleAiBlogRoute;
import app.routes.GoogleResearchRoute;
import app.routes.GoogleSecurityRoute;
import app.routes.HackadayRoute;
import app.routes.HuggingFaceBlogRoute;
import app.routes.IfanrRoute;
import app.routes.ImportAiRoute;
import app.routes.InfoqChinaRoute;
import app.routes.InterconnectsRoute;
import app.routes.IthomeRoute;
import app.routes.JuejinRoute;
import app.routes.KarpathyBlogRoute;
import app.routes.KexueRoute;
import app.routes.KrebsSecurityRoute;
import app.routes.LandianNewsRoute;
import app.routes.LatentSpaceRoute;
import app.routes.LeiphoneRoute;
import app.routes.LilianWengRoute;
import app.routes.LwnHeadlinesRoute;
import app.routes.MartinFowlerRoute;
import app.routes.MicrosoftResearchRoute;
import app.routes.NetflixTechBlogRoute;
import app.routes.NvidiaTechBlogRoute;
import app.routes.OneUsefulThingRoute;
import app.routes.OpenAiNewsRoute;
import app.routes.OschinaNewsRoute;
import app.routes.PragmaticEngineerRoute;
import app.routes.QbitAiRoute;
import app.routes.RaspberryPiRoute;
import app.routes.RuanYifengRoute;
import app.routes.SchneierSecurityRoute;
import app.routes.SimonWillisonRoute;
import app.routes.SolidotRoute;
import app.routes.TakaraDailyPapersRoute;
import app.routes.ZhidxRoute;
import app.routes.github.ClaudeCodeReleasesRoute;
import app.routes.github.ComfyUiReleasesRoute;
import app.routes.github.LlamaCppReleasesRoute;
import app.routes.github.OllamaReleasesRoute;
import app.routes.github.OpenWebUiReleasesRoute;
import app.routes.github.TransformersReleasesRoute;
import app.routes.github.VllmReleasesRoute;
import app.routes.github.VscodeReleasesRoute;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * The technology part of the curated catalog: AI, software, security and tech-news feeds.
 */
final class CuratedTechRoutes {
    private CuratedTechRoutes() {}

    static List<Route> create(FetchClient defaultFetchClient, FeedStore feedStore, ObjectMapper objectMapper,
                              CacheService cacheService, CredentialConfigStore credentialConfigStore) {
        return List.of(
                    new Route("/huggingface/blog",
                            new HuggingFaceBlogRoute(defaultFetchClient, feedStore),
                            "Hugging Face 官方博客 · 开源模型、工具与实践",
                            Topic.AI, "官方 RSS",
                            "huggingface.co/blog/feed.xml；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/google/research",
                            new GoogleResearchRoute(defaultFetchClient, feedStore),
                            "Google Research · 一手研究与技术解读",
                            Topic.AI, "官方 RSS",
                            "research.google/blog/rss/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/openai/news",
                            new OpenAiNewsRoute(defaultFetchClient, feedStore),
                            "OpenAI 官方动态 · 研究、产品与工程",
                            Topic.AI, "官方 RSS",
                            "openai.com/news/rss.xml；无需密钥，只读取列表；历史内容较多，建议每 180 分钟刷新", 180),
                    new Route("/deepmind/blog",
                            new DeepMindBlogRoute(defaultFetchClient, feedStore),
                            "Google DeepMind · 前沿 AI 研究与科学应用（英文）",
                            Topic.AI, "官方 RSS",
                            "deepmind.google/blog/rss.xml；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/bair/blog",
                            new BairBlogRoute(defaultFetchClient, feedStore),
                            "BAIR 伯克利 AI 研究博客 · 学术前沿解读（英文）",
                            Topic.AI, "官方 RSS",
                            "bair.berkeley.edu/blog/feed.xml；无需密钥，只读取列表；更新频率低，建议每 1440 分钟刷新", 1440),
                    new Route("/simonwillison/weblog",
                            new SimonWillisonRoute(defaultFetchClient, feedStore),
                            "Simon Willison · LLM 工具、提示工程与实践笔记（英文）",
                            Topic.AI, "官方 RSS",
                            "simonwillison.net/atom/everything/；无需密钥，只读取列表；含短链接与引用，更新频繁；建议每 120 分钟刷新", 120),
                    new Route("/aihot/news",
                            new AiHotRoute(defaultFetchClient, feedStore),
                            "AIHOT · 中文 AI 资讯精选摘要，附原文链接",
                            Topic.AI, "官方 RSS",
                            "aihot.news/feed.xml；无需密钥，只读取最新 50 条精选；更新频繁，建议每 60 分钟刷新", 60),
                    new Route("/github/open-source",
                            new GithubOpenSourceRoute(defaultFetchClient, feedStore),
                            "GitHub Open Source · 开源项目、维护者与社区报道",
                            Topic.SOFTWARE, "官方 RSS",
                            "github.blog/open-source/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/github/changelog",
                            new GithubChangelogRoute(defaultFetchClient, feedStore),
                            "GitHub Changelog · 开发平台更新",
                            Topic.SOFTWARE, "官方 RSS",
                            "github.blog/changelog/feed/；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/ollama/releases",
                            new OllamaReleasesRoute(defaultFetchClient, feedStore),
                            "Ollama · 本地模型工具版本发布",
                            Topic.AI, "官方 Atom",
                            "github.com/ollama/ollama/releases.atom；无需密钥，包含预发布版本；不占用 GitHub API 限额；建议每 180 分钟刷新", 180),
                    new Route("/huggingface/transformers/releases",
                            new TransformersReleasesRoute(defaultFetchClient, feedStore),
                            "Transformers · 模型库版本发布",
                            Topic.AI, "官方 Atom",
                            "github.com/huggingface/transformers/releases.atom；无需密钥，包含预发布版本；不占用 GitHub API 限额；建议每 180 分钟刷新", 180),
                    new Route("/microsoft/research",
                            new MicrosoftResearchRoute(defaultFetchClient, feedStore),
                            "Microsoft Research 博客 · 研究进展与论文解读（英文）",
                            Topic.AI, "官方 RSS",
                            "www.microsoft.com/en-us/research/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/nvidia/blog",
                            new NvidiaTechBlogRoute(defaultFetchClient, feedStore),
                            "NVIDIA 技术博客 · GPU、推理与 AI 工程实践（英文）",
                            Topic.AI, "官方 RSS",
                            "developer.nvidia.com/blog/feed；无需密钥，只读取列表；每次返回最近 100 篇；建议每 180 分钟刷新", 180),
                    new Route("/importai/newsletter",
                            new ImportAiRoute(defaultFetchClient, feedStore),
                            "Import AI · Jack Clark 的 AI 研究与政策周报（英文）",
                            Topic.AI, "官方 RSS",
                            "importai.substack.com/feed；无需密钥，只读取列表；每周更新；建议每 720 分钟刷新", 720),
                    new Route("/latentspace/posts",
                            new LatentSpaceRoute(defaultFetchClient, feedStore),
                            "Latent Space · AI 工程师播客与每日 AINews（英文）",
                            Topic.AI, "官方 RSS",
                            "www.latent.space/feed；无需密钥，只读取列表；含每日 AINews 汇总；建议每 180 分钟刷新", 180),
                    new Route("/interconnects/posts",
                            new InterconnectsRoute(defaultFetchClient, feedStore),
                            "Interconnects · Nathan Lambert 谈开源模型与后训练（英文）",
                            Topic.AI, "官方 RSS",
                            "www.interconnects.ai/feed；无需密钥，只读取列表；建议每 720 分钟刷新", 720),
                    new Route("/oneusefulthing/posts",
                            new OneUsefulThingRoute(defaultFetchClient, feedStore),
                            "One Useful Thing · Ethan Mollick 谈 AI 与工作、教育（英文）",
                            Topic.AI, "官方 RSS",
                            "www.oneusefulthing.org/feed；无需密钥，只读取列表；更新频率低；建议每 1440 分钟刷新", 1440),
                    new Route("/lilianweng/blog",
                            new LilianWengRoute(defaultFetchClient, feedStore),
                            "Lil'Log · Lilian Weng 的长篇 AI 技术综述（英文）",
                            Topic.AI, "官方 RSS",
                            "lilianweng.github.io/index.xml；无需密钥，只读取列表；更新频率低；建议每 1440 分钟刷新", 1440),
                    new Route("/qbitai/news",
                            new QbitAiRoute(defaultFetchClient, feedStore),
                            "量子位 · AI 前沿资讯与产业报道",
                            Topic.AI, "官方 RSS",
                            "www.qbitai.com/feed；无需密钥，只读取列表；更新频繁；建议每 60 分钟刷新", 60),
                    new Route("/leiphone/news",
                            new LeiphoneRoute(defaultFetchClient, feedStore),
                            "雷峰网 · 人工智能、具身智能与芯片产业报道",
                            Topic.AI, "官方 RSS",
                            "www.leiphone.com/feed；无需密钥，只读取列表；含正文全文；建议每 120 分钟刷新", 120),
                    new Route("/github/ai-ml",
                            new GithubAiMlRoute(defaultFetchClient, feedStore),
                            "GitHub AI & ML · Copilot、智能体与 AI 开发实践",
                            Topic.AI, "官方 RSS",
                            "github.blog/ai-and-ml/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/vllm/releases",
                            new VllmReleasesRoute(defaultFetchClient, feedStore),
                            "vLLM · 大模型推理引擎版本发布",
                            Topic.AI, "官方 Atom",
                            "github.com/vllm-project/vllm/releases.atom；无需密钥，包含预发布版本；不占用 GitHub API 限额；建议每 180 分钟刷新", 180),
                    new Route("/google/ai-blog",
                            new GoogleAiBlogRoute(defaultFetchClient, feedStore),
                            "Google AI 博客 · Gemini 与 Google AI 产品动态（英文）",
                            Topic.AI, "官方 RSS",
                            "blog.google/technology/ai/rss/；无需密钥，只读取列表；建议每 180 分钟刷新", 180),
                    new Route("/raschka/ahead-of-ai",
                            new AheadOfAiRoute(defaultFetchClient, feedStore),
                            "Ahead of AI · Sebastian Raschka 的 LLM 研究解读（英文）",
                            Topic.AI, "官方 RSS",
                            "magazine.sebastianraschka.com/feed；无需密钥，只读取列表；含正文全文，单次约 2.5 MB；建议每 720 分钟刷新", 720),
                    new Route("/karpathy/blog",
                            new KarpathyBlogRoute(defaultFetchClient, feedStore),
                            "Andrej Karpathy 博客 · 大模型与 AI 教育长文（英文）",
                            Topic.AI, "官方 RSS",
                            "karpathy.bearblog.dev/feed/；无需密钥，只读取列表；更新频率低；建议每 1440 分钟刷新", 1440),
                    new Route("/kexue/blog",
                            new KexueRoute(defaultFetchClient, feedStore),
                            "科学空间 · 苏剑林的深度学习与大模型原理推导",
                            Topic.AI, "官方 RSS",
                            "kexue.fm/feed；无需密钥，只读取列表；更新频率低；建议每 720 分钟刷新", 720),
                    new Route("/takara/daily-papers",
                            new TakaraDailyPapersRoute(defaultFetchClient, feedStore),
                            "Takara TLDR · Hugging Face 每日热门 AI 论文摘要（英文）",
                            Topic.AI, "官方 RSS",
                            "papers.takara.ai/api/feed；无需密钥，只读取列表；每天约 50 篇；建议每 360 分钟刷新", 360),
                    new Route("/open-webui/releases",
                            new OpenWebUiReleasesRoute(defaultFetchClient, feedStore),
                            "Open WebUI · 自托管大模型界面版本发布",
                            Topic.AI, "官方 Atom",
                            "github.com/open-webui/open-webui/releases.atom；无需密钥，不占用 GitHub API 限额；建议每 360 分钟刷新", 360),
                    new Route("/claude-code/releases",
                            new ClaudeCodeReleasesRoute(defaultFetchClient, feedStore),
                            "Claude Code · 命令行编程智能体版本发布",
                            Topic.AI, "官方 Atom",
                            "github.com/anthropics/claude-code/releases.atom；无需密钥，不占用 GitHub API 限额；更新频繁；建议每 180 分钟刷新", 180),
                    new Route("/comfyui/releases",
                            new ComfyUiReleasesRoute(defaultFetchClient, feedStore),
                            "ComfyUI · 图像与视频生成工作流版本发布",
                            Topic.AI, "官方 Atom",
                            "github.com/Comfy-Org/ComfyUI/releases.atom；无需密钥，不占用 GitHub API 限额；建议每 360 分钟刷新", 360),
                    new Route("/llama-cpp/releases",
                            new LlamaCppReleasesRoute(defaultFetchClient, feedStore),
                            "llama.cpp · 本地大模型推理引擎版本发布",
                            Topic.AI, "官方 Atom",
                            "github.com/ggml-org/llama.cpp/releases.atom；无需密钥，不占用 GitHub API 限额；建议每 360 分钟刷新", 360),
                    new Route("/cloudflare/blog",
                            new CloudflareBlogRoute(defaultFetchClient, feedStore),
                            "Cloudflare · 网络、性能与工程实践（英文）",
                            Topic.SOFTWARE, "官方 RSS",
                            "blog.cloudflare.com/rss/；无需密钥，只读取列表；建议每 180 分钟刷新", 180),
                    new Route("/martinfowler/articles",
                            new MartinFowlerRoute(defaultFetchClient, feedStore),
                            "Martin Fowler · 软件架构、设计与重构（英文）",
                            Topic.SOFTWARE, "官方 RSS",
                            "martinfowler.com/feed.atom；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/netflix/techblog",
                            new NetflixTechBlogRoute(defaultFetchClient, feedStore),
                            "Netflix TechBlog · 分布式系统与工程实践（英文）",
                            Topic.SOFTWARE, "官方 RSS",
                            "netflixtechblog.com/feed；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/infoq/china",
                            new InfoqChinaRoute(defaultFetchClient, feedStore),
                            "InfoQ 中文 · 软件开发与架构资讯",
                            Topic.SOFTWARE, "官方 RSS",
                            "www.infoq.cn/feed；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/lwn/headlines",
                            new LwnHeadlinesRoute(defaultFetchClient, feedStore),
                            "LWN.net · Linux 内核与开源生态深度报道（英文）",
                            Topic.SOFTWARE, "官方 RSS",
                            "lwn.net/headlines/rss；无需密钥，只读取列表；订阅文章发布一周后免费；建议每 180 分钟刷新", 180),
                    new Route("/github/engineering",
                            new GithubEngineeringRoute(defaultFetchClient, feedStore),
                            "GitHub Engineering · GitHub 工程团队的架构与基础设施实践",
                            Topic.SOFTWARE, "官方 RSS",
                            "github.blog/engineering/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/oschina/news",
                            new OschinaNewsRoute(defaultFetchClient, feedStore),
                            "开源中国 · 开源项目、软件发布与开发者资讯",
                            Topic.SOFTWARE, "官方 RSS",
                            "www.oschina.net/news/rss；无需密钥，只读取列表；更新频繁；建议每 120 分钟刷新", 120),
                    new Route("/pragmaticengineer/newsletter",
                            new PragmaticEngineerRoute(defaultFetchClient, feedStore),
                            "The Pragmatic Engineer · 软件工程团队与行业观察（英文）",
                            Topic.SOFTWARE, "官方 RSS",
                            "newsletter.pragmaticengineer.com/feed；无需密钥，只读取列表；付费文章只有摘要；建议每 720 分钟刷新", 720),
                    new Route("/github/product-news",
                            new GithubProductNewsRoute(defaultFetchClient, feedStore),
                            "GitHub 产品动态 · Copilot 与平台新功能发布",
                            Topic.SOFTWARE, "官方 RSS",
                            "github.blog/news-insights/product-news/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/vscode/releases",
                            new VscodeReleasesRoute(defaultFetchClient, feedStore),
                            "VS Code · 编辑器版本发布",
                            Topic.SOFTWARE, "官方 Atom",
                            "github.com/microsoft/vscode/releases.atom；无需密钥，不占用 GitHub API 限额；建议每 720 分钟刷新", 720),
                    new Route("/juejin/posts",
                            new JuejinRoute(defaultFetchClient, feedStore),
                            "掘金 · 开发者社区热门技术文章",
                            Topic.SOFTWARE, "官方 RSS",
                            "juejin.cn/rss；无需密钥，只读取列表；建议每 180 分钟刷新", 180),
                    new Route("/google/security",
                            new GoogleSecurityRoute(defaultFetchClient, feedStore),
                            "Google Security · 安全研究与防护更新（英文）",
                            Topic.SECURITY, "官方 RSS",
                            "blog.google/security/rss/；无需密钥，只读取列表；建议每 180 分钟刷新", 180),
                    new Route("/krebs/security",
                            new KrebsSecurityRoute(defaultFetchClient, feedStore),
                            "Krebs on Security · 安全事件调查报道（英文）",
                            Topic.SECURITY, "官方 RSS",
                            "krebsonsecurity.com/feed/；无需密钥，只读取列表；建议每 180 分钟刷新", 180),
                    new Route("/schneier/security",
                            new SchneierSecurityRoute(defaultFetchClient, feedStore),
                            "Schneier on Security · 安全、隐私与政策评论（英文）",
                            Topic.SECURITY, "官方 RSS",
                            "www.schneier.com/feed/；无需密钥，只读取列表；建议每 180 分钟刷新", 180),
                    new Route("/github/security",
                            new GithubSecurityRoute(defaultFetchClient, feedStore),
                            "GitHub Security · 供应链安全、漏洞研究与安全实践",
                            Topic.SECURITY, "官方 RSS",
                            "github.blog/security/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/ruanyifeng/blog",
                            new RuanYifengRoute(defaultFetchClient, feedStore),
                            "阮一峰 · 网络日志与科技爱好者周刊",
                            Topic.TECH, "官方 RSS",
                            "www.ruanyifeng.com/blog/atom.xml；无需密钥，只读取列表；建议每 1440 分钟刷新", 1440),
                    new Route("/solidot/news",
                            new SolidotRoute(defaultFetchClient, feedStore),
                            "Solidot · 科技、科学与开源资讯中文编译",
                            Topic.TECH, "官方 RSS",
                            "www.solidot.org/index.rss；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/arstechnica/latest",
                            new ArsTechnicaRoute(defaultFetchClient, feedStore),
                            "Ars Technica · 科技、科学与政策深度报道（英文）",
                            Topic.TECH, "官方 RSS",
                            "feeds.arstechnica.com/arstechnica/index；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/ifanr/latest",
                            new IfanrRoute(defaultFetchClient, feedStore),
                            "爱范儿 · 消费科技、产品评测与数字生活",
                            Topic.TECH, "官方 RSS",
                            "www.ifanr.com/feed；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/geekpark/news",
                            new GeekParkRoute(defaultFetchClient, feedStore),
                            "极客公园 · 科技公司、创新产品与行业访谈",
                            Topic.TECH, "官方 RSS",
                            "www.geekpark.net/rss；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/hackaday/posts",
                            new HackadayRoute(defaultFetchClient, feedStore),
                            "Hackaday · 硬件改造、电子工程与创客项目（英文）",
                            Topic.TECH, "官方 RSS",
                            "hackaday.com/blog/feed/；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/raspberrypi/news",
                            new RaspberryPiRoute(defaultFetchClient, feedStore),
                            "Raspberry Pi · 树莓派项目实践、软硬件与产品动态（英文）",
                            Topic.TECH, "官方 RSS",
                            "www.raspberrypi.com/news/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/ithome/news",
                            new IthomeRoute(defaultFetchClient, feedStore),
                            "IT之家 · 数码、软件与科技快讯",
                            Topic.TECH, "官方 RSS",
                            "www.ithome.com/rss/；无需密钥，只读取列表；更新频繁；建议每 60 分钟刷新", 60),
                    new Route("/zhidx/news",
                            new ZhidxRoute(defaultFetchClient, feedStore),
                            "智东西 · 智能产业、芯片与消费电子报道",
                            Topic.TECH, "官方 RSS",
                            "www.zhidx.com/rss；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/appinn/posts",
                            new AppinnRoute(defaultFetchClient, feedStore),
                            "小众软件 · 实用软件、工具与效率技巧",
                            Topic.TECH, "官方 RSS",
                            "www.appinn.com/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/landian/news",
                            new LandianNewsRoute(defaultFetchClient, feedStore),
                            "蓝点网 · 软件、系统与互联网科技快讯",
                            Topic.TECH, "官方 RSS",
                            "www.landiannews.com/feed；无需密钥，只读取列表；建议每 120 分钟刷新", 120));
    }
}
