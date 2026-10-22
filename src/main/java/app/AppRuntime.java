package app;

import app.core.CacheService;
import app.core.DefaultFetchClient;
import app.core.FetchClient;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteMatch;
import app.core.RouteRegistry;
import app.routes.AeonMagazineRoute;
import app.routes.ApertureRoute;
import app.routes.ApsResearchNewsRoute;
import app.routes.ArchDailyRoute;
import app.routes.ArxivRoute;
import app.routes.CarbonBriefRoute;
import app.routes.CctvNewsRoute;
import app.routes.ChinanewsCultureRoute;
import app.routes.ChinanewsFinanceRoute;
import app.routes.ChinanewsSocietyRoute;
import app.routes.CloudflareBlogRoute;
import app.routes.CnblogsRoute;
import app.routes.ColossalRoute;
import app.routes.DezeenRoute;
import app.routes.DiygodRoute;
import app.routes.EcbBlogRoute;
import app.routes.EdSurgeNewsRoute;
import app.routes.FactorioBlogRoute;
import app.routes.FedsNotesRoute;
import app.routes.GcoresRoute;
import app.routes.GenericRssRoute;
import app.routes.GithubChangelogRoute;
import app.routes.GithubOpenSourceRoute;
import app.routes.GodotNewsRoute;
import app.routes.GoogleResearchRoute;
import app.routes.GoogleSecurityRoute;
import app.routes.GuardianWorldRoute;
import app.routes.HackadayRoute;
import app.routes.HackerNewsRoute;
import app.routes.HechingerReportRoute;
import app.routes.HuggingFaceBlogRoute;
import app.routes.InfoqChinaRoute;
import app.routes.JstorDailyRoute;
import app.routes.KffHealthNewsRoute;
import app.routes.KrebsSecurityRoute;
import app.routes.MartinFowlerRoute;
import app.routes.MritdRoute;
import app.routes.MsDevOpsRoute;
import app.routes.NasaNewsRoute;
import app.routes.NetflixTechBlogRoute;
import app.routes.NoaaNewsRoute;
import app.routes.OpenAiNewsRoute;
import app.routes.OurWorldInDataRoute;
import app.routes.PewResearchRoute;
import app.routes.ProductHuntRoute;
import app.routes.PsycheMagazineRoute;
import app.routes.PublicDomainReviewRoute;
import app.routes.QuantaMagazineRoute;
import app.routes.RaspberryPiRoute;
import app.routes.RthkLocalRoute;
import app.routes.RuanYifengRoute;
import app.routes.SchneierSecurityRoute;
import app.routes.ScienceNewsRoute;
import app.routes.SmashingMagazineRoute;
import app.routes.SolidotRoute;
import app.routes.SspaiRoute;
import app.routes.StatNewsRoute;
import app.routes.TechCrunchRoute;
import app.routes.ThirtySixKrRoute;
import app.routes.UnNewsChineseRoute;
import app.routes.WiredRoute;
import app.routes.XinhuaRoute;
import app.routes.YystvRoute;
import app.routes.bbc.BBCNewsRoute;
import app.routes.bbc.BbcLearningEnglishRoute;
import app.routes.bilibili.BilibiliDynamicRoute;
import app.routes.bilibili.BilibiliFollowingsArticleRoute;
import app.routes.bilibili.BilibiliFollowingsDynamicRoute;
import app.routes.bilibili.BilibiliFollowingsVideoRoute;
import app.routes.bilibili.BilibiliVideoRoute;
import app.routes.github.GithubReleaseRoute;
import app.routes.github.OllamaReleasesRoute;
import app.routes.github.TransformersReleasesRoute;
import app.routes.huanqiu.HuanqiuRoute;
import app.routes.huanqiu.HuanqiuTechRoute;
import app.routes.telegram.NewlearnerChannelRoute;
import app.routes.telegram.ScitechFansChannelRoute;
import app.routes.telegram.SolidotChannelRoute;
import app.routes.telegram.TelegramChannelRoute;
import app.routes.twitter.AnthropicTwitterRoute;
import app.routes.twitter.GithubTwitterRoute;
import app.routes.twitter.HuggingFaceTwitterRoute;
import app.routes.twitter.KarpathyTwitterRoute;
import app.routes.twitter.OpenAiTwitterRoute;
import app.routes.twitter.SimonWillisonTwitterRoute;
import app.routes.twitter.TwitterUserRoute;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Owns the application-wide services and explicit route registration.
 */
@Component
public class AppRuntime {
    private final FetchClient defaultFetchClient = new DefaultFetchClient();
    private final CacheService cacheService;
    private final FeedFetchCoordinator fetchCoordinator = new FeedFetchCoordinator();
    private final RouteRegistry routeRegistry;
    private final RouteConfigStore routeConfigStore;
    private final CredentialConfigStore credentialConfigStore;

    /**
     * Builds the default runtime used by Spring injection.
     *
     * @param objectMapper JSON mapper shared with configuration persistence
     */
    @Autowired
    public AppRuntime(ObjectMapper objectMapper, FeedStore feedStore) {
        this.cacheService = new CacheService();
        this.credentialConfigStore = new CredentialConfigStore(
                Path.of("data/credential-config.json"), objectMapper);
        this.routeRegistry = new RouteRegistry(List.of(
                new Route("/huggingface/blog",
                        new HuggingFaceBlogRoute(defaultFetchClient, feedStore),
                        "Hugging Face 官方博客 · 开源模型、工具与实践",
                        "AI", "官方 RSS",
                        "huggingface.co/blog/feed.xml；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                new Route("/google/research",
                        new GoogleResearchRoute(defaultFetchClient, feedStore),
                        "Google Research · 一手研究与技术解读",
                        "AI", "官方 RSS",
                        "research.google/blog/rss/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                new Route("/openai/news",
                        new OpenAiNewsRoute(defaultFetchClient, feedStore),
                        "OpenAI 官方动态 · 研究、产品与工程",
                        "AI", "官方 RSS",
                        "openai.com/news/rss.xml；无需密钥，只读取列表；历史内容较多，建议每 180 分钟刷新", 180),
                new Route("/github/open-source",
                        new GithubOpenSourceRoute(defaultFetchClient, feedStore),
                        "GitHub Open Source · 开源项目、维护者与社区报道",
                        "GitHub 项目", "官方 RSS",
                        "github.blog/open-source/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                new Route("/github/changelog",
                        new GithubChangelogRoute(defaultFetchClient, feedStore),
                        "GitHub Changelog · 开发平台更新",
                        "GitHub 项目", "官方 RSS",
                        "github.blog/changelog/feed/；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                new Route("/ollama/releases",
                        new OllamaReleasesRoute(defaultFetchClient, objectMapper),
                        "Ollama · 本地模型工具版本发布",
                        "GitHub 项目", "官方 JSON API",
                        "GitHub ollama/ollama Releases；无需密钥，包含标记为预发布的版本；建议每 180 分钟刷新，共享每 IP 每小时 60 次未认证请求限额", 180),
                new Route("/huggingface/transformers/releases",
                        new TransformersReleasesRoute(defaultFetchClient, objectMapper),
                        "Transformers · 模型库版本发布",
                        "GitHub 项目", "官方 JSON API",
                        "GitHub huggingface/transformers Releases；无需密钥，包含标记为预发布的版本；建议每 180 分钟刷新，共享每 IP 每小时 60 次未认证请求限额", 180),
                new Route("/chinanews/society",
                        new ChinanewsSocietyRoute(defaultFetchClient, feedStore),
                        "中新网社会 · 大陆社会与民生",
                        "社会新闻", "官方 RSS",
                        "chinanews.com.cn/rss/society.xml；无需密钥，只读取列表；建议每 30 分钟刷新", 30),
                new Route("/un/news/zh",
                        new UnNewsChineseRoute(defaultFetchClient, feedStore),
                        "联合国新闻中文 · 人道、健康、气候与公共议题",
                        "社会新闻", "官方 RSS",
                        "news.un.org 中文 RSS；无需密钥，只读取列表；建议每 60 分钟刷新", 60),
                new Route("/rthk/local",
                        new RthkLocalRoute(defaultFetchClient, feedStore),
                        "香港电台本地 · 社会与公共政策（繁体）",
                        "社会新闻", "官方 RSS",
                        "rthk.hk 官方本地新闻 RSS；无需密钥，只读取列表；建议每 30 分钟刷新", 30),
                new Route("/guardian/world",
                        new GuardianWorldRoute(defaultFetchClient, feedStore),
                        "The Guardian 世界 · 国际与社会新闻（英文）",
                        "社会新闻", "官方 RSS",
                        "theguardian.com/world/rss；无需密钥，只读取列表；建议每 60 分钟刷新", 60),
                new Route("/nasa/news",
                        new NasaNewsRoute(defaultFetchClient, feedStore),
                        "NASA · 航天任务与科学动态（英文）",
                        "科学探索", "官方 RSS",
                        "www.nasa.gov/feed/；无需密钥，只读取列表；建议每 180 分钟刷新", 180),
                new Route("/quanta/magazine",
                        new QuantaMagazineRoute(defaultFetchClient, feedStore),
                        "Quanta Magazine · 数学与基础科学深度报道（英文）",
                        "科学探索", "官方 RSS",
                        "www.quantamagazine.org/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                new Route("/sciencenews/latest",
                        new ScienceNewsRoute(defaultFetchClient, feedStore),
                        "Science News · 综合科学新闻（英文摘要）",
                        "科学探索", "官方 RSS",
                        "www.sciencenews.org/feed；无需密钥，只读取列表；建议每 180 分钟刷新；官网全文可能有阅读额度或订阅限制", 180),
                new Route("/carbonbrief/news",
                        new CarbonBriefRoute(defaultFetchClient, feedStore),
                        "Carbon Brief · 气候、能源与政策分析（英文）",
                        "气候环境", "官方 RSS",
                        "www.carbonbrief.org/feed；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                new Route("/noaa/news",
                        new NoaaNewsRoute(defaultFetchClient, feedStore),
                        "NOAA · 海洋、天气与气候机构动态（英文）",
                        "气候环境", "官方 RSS",
                        "www.noaa.gov/rss.xml；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                new Route("/cloudflare/blog",
                        new CloudflareBlogRoute(defaultFetchClient, feedStore),
                        "Cloudflare · 网络、性能与工程实践（英文）",
                        "软件工程", "官方 RSS",
                        "blog.cloudflare.com/rss/；无需密钥，只读取列表；建议每 180 分钟刷新", 180),
                new Route("/martinfowler/articles",
                        new MartinFowlerRoute(defaultFetchClient, feedStore),
                        "Martin Fowler · 软件架构、设计与重构（英文）",
                        "软件工程", "官方 RSS",
                        "martinfowler.com/feed.atom；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                new Route("/netflix/techblog",
                        new NetflixTechBlogRoute(defaultFetchClient, feedStore),
                        "Netflix TechBlog · 分布式系统与工程实践（英文）",
                        "软件工程", "官方 RSS",
                        "netflixtechblog.com/feed；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                new Route("/infoq/china",
                        new InfoqChinaRoute(defaultFetchClient, feedStore),
                        "InfoQ 中文 · 软件开发与架构资讯",
                        "软件工程", "官方 RSS",
                        "www.infoq.cn/feed；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                new Route("/google/security",
                        new GoogleSecurityRoute(defaultFetchClient, feedStore),
                        "Google Security · 安全研究与防护更新（英文）",
                        "网络安全", "官方 RSS",
                        "blog.google/security/rss/；无需密钥，只读取列表；建议每 180 分钟刷新", 180),
                new Route("/krebs/security",
                        new KrebsSecurityRoute(defaultFetchClient, feedStore),
                        "Krebs on Security · 安全事件调查报道（英文）",
                        "网络安全", "官方 RSS",
                        "krebsonsecurity.com/feed/；无需密钥，只读取列表；建议每 180 分钟刷新", 180),
                new Route("/schneier/security",
                        new SchneierSecurityRoute(defaultFetchClient, feedStore),
                        "Schneier on Security · 安全、隐私与政策评论（英文）",
                        "网络安全", "官方 RSS",
                        "www.schneier.com/feed/；无需密钥，只读取列表；建议每 180 分钟刷新", 180),
                new Route("/ecb/blog",
                        new EcbBlogRoute(defaultFetchClient, feedStore),
                        "欧洲央行博客 · 欧元区宏观与货币政策（英文）",
                        "商业经济", "官方 RSS",
                        "www.ecb.europa.eu/rss/blog.html；无需密钥，只读取列表；建议每 120 分钟刷新；上游提供标题、日期与原文链接，不含摘要", 120),
                new Route("/federalreserve/feds-notes",
                        new FedsNotesRoute(defaultFetchClient, feedStore),
                        "美联储 FEDS Notes · 经济金融研究札记（英文）",
                        "商业经济", "官方 RSS",
                        "www.federalreserve.gov/feeds/feds_notes.xml；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                new Route("/chinanews/finance",
                        new ChinanewsFinanceRoute(defaultFetchClient, feedStore),
                        "中新网财经 · 国内经济与产业资讯",
                        "商业经济", "官方 RSS",
                        "www.chinanews.com.cn/rss/finance.xml；无需密钥，只读取列表；建议每 60 分钟刷新", 60),
                new Route("/smashing/articles",
                        new SmashingMagazineRoute(defaultFetchClient, feedStore),
                        "Smashing Magazine · 网页设计、UX 与可访问性（英文）",
                        "设计文化", "官方 RSS",
                        "www.smashingmagazine.com/feed/；无需密钥，只读取列表；建议每 180 分钟刷新；列表响应较大，建议低频刷新", 180),
                new Route("/aeon/essays",
                        new AeonMagazineRoute(defaultFetchClient, feedStore),
                        "Aeon · 哲学、心理与人文深度文章（英文）",
                        "设计文化", "官方 RSS",
                        "aeon.co/feed.rss；无需密钥，只读取列表；建议每 120 分钟刷新；RSS 供个人非商业阅读，保留原文署名与链接", 120),
                new Route("/chinanews/culture",
                        new ChinanewsCultureRoute(defaultFetchClient, feedStore),
                        "中新网文化 · 文化、艺术与历史资讯",
                        "设计文化", "官方 RSS",
                        "www.chinanews.com.cn/rss/culture.xml；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                new Route("/ruanyifeng/blog",
                        new RuanYifengRoute(defaultFetchClient, feedStore),
                        "阮一峰 · 网络日志与科技爱好者周刊",
                        "科技精选", "官方 RSS",
                        "www.ruanyifeng.com/blog/atom.xml；无需密钥，只读取列表；建议每 1440 分钟刷新", 1440),
                new Route("/solidot/news",
                        new SolidotRoute(defaultFetchClient, feedStore),
                        "Solidot · 科技、科学与开源资讯中文编译",
                        "科技精选", "官方 RSS",
                        "www.solidot.org/index.rss；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                new Route("/kff/health-news",
                        new KffHealthNewsRoute(defaultFetchClient, feedStore),
                        "KFF Health News · 公共健康、医疗制度与健康政策深度报道（英文）",
                        "健康医学", "官方 RSS",
                        "kffhealthnews.org/feed/；无需密钥，只读取列表；建议每 360 分钟刷新；主要报道美国公共健康与医疗制度", 360),
                new Route("/stat/news",
                        new StatNewsRoute(defaultFetchClient, feedStore),
                        "STAT · 医学、生命科学与医疗行业新闻（英文；部分原文需订阅）",
                        "健康医学", "官方 RSS",
                        "www.statnews.com/feed/；无需密钥，只读取列表；建议每 180 分钟刷新；部分原文需 STAT+ 订阅", 180),
                new Route("/psyche/magazine",
                        new PsycheMagazineRoute(defaultFetchClient, feedStore),
                        "Psyche · 心理、认知与自我理解科普（英文）",
                        "心理认知", "官方 RSS",
                        "psyche.co/feed.rss；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                new Route("/aps/research",
                        new ApsResearchNewsRoute(defaultFetchClient, feedStore),
                        "APS 心理科学研究 · 心理学研究发布与学术进展（英文）",
                        "心理认知", "官方 RSS",
                        "www.psychologicalscience.org/news/releases/feed；无需密钥，只读取列表；建议每 720 分钟刷新", 720),
                new Route("/edsurge/news",
                        new EdSurgeNewsRoute(defaultFetchClient, feedStore),
                        "EdSurge · 教育创新、教学实践与教育科技报道（英文）",
                        "教育学习", "官方 RSS",
                        "www.edsurge.com/articles_rss；无需密钥，只读取列表；建议每 360 分钟刷新；主要覆盖美国教育议题", 360),
                new Route("/hechinger/report",
                        new HechingerReportRoute(defaultFetchClient, feedStore),
                        "The Hechinger Report · 教育公平、学习成效与学校创新深度报道（英文）",
                        "教育学习", "官方 RSS",
                        "hechingerreport.org/feed/；无需密钥，只读取列表；建议每 360 分钟刷新；主要覆盖美国教育议题", 360),
                new Route("/ourworldindata/articles",
                        new OurWorldInDataRoute(defaultFetchClient, feedStore),
                        "Our World in Data · 人口、健康与全球发展数据研究（英文）",
                        "数据洞察", "官方 RSS",
                        "ourworldindata.org/atom.xml；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                new Route("/pewresearch/publications",
                        new PewResearchRoute(defaultFetchClient, feedStore),
                        "Pew Research Center · 民意调查、人口与社会趋势研究（英文）",
                        "数据洞察", "官方 RSS",
                        "www.pewresearch.org/publications/feed/；无需密钥，只读取列表；建议每 360 分钟刷新；含报告章节，提供摘要", 360),
                new Route("/hackaday/posts",
                        new HackadayRoute(defaultFetchClient, feedStore),
                        "Hackaday · 硬件改造、电子工程与创客项目（英文）",
                        "硬件创客", "官方 RSS",
                        "hackaday.com/blog/feed/；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                new Route("/raspberrypi/news",
                        new RaspberryPiRoute(defaultFetchClient, feedStore),
                        "Raspberry Pi · 树莓派项目实践、软硬件与产品动态（英文）",
                        "硬件创客", "官方 RSS",
                        "www.raspberrypi.com/news/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                new Route("/godot/news",
                        new GodotNewsRoute(defaultFetchClient, feedStore),
                        "Godot Engine · 游戏引擎开发进展、技术文章与版本发布（英文）",
                        "游戏开发", "官方 RSS",
                        "godotengine.org/atom.xml；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                new Route("/factorio/blog",
                        new FactorioBlogRoute(defaultFetchClient, feedStore),
                        "Factorio Friday Facts · 工厂游戏开发日志、性能优化与设计实践（英文）",
                        "游戏开发", "官方 RSS",
                        "factorio.com/blog/rss；无需密钥，只读取列表；建议每 720 分钟刷新；开发日志发布频率不固定", 720),
                new Route("/jstor/daily",
                        new JstorDailyRoute(defaultFetchClient, feedStore),
                        "JSTOR Daily · 以学术研究解读历史、文化与公共议题（英文）",
                        "人文历史", "官方 RSS",
                        "daily.jstor.org/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                new Route("/public-domain-review/latest",
                        new PublicDomainReviewRoute(defaultFetchClient, feedStore),
                        "公共领域评论 · 探索历史艺术、文学与思想中的公共领域珍藏（英文）",
                        "人文历史", "官方 RSS",
                        "publicdomainreview.org/rss.xml；无需密钥，只读取列表；建议每 1440 分钟刷新；以摘要为主，适合低频阅读", 1440),
                new Route("/colossal/latest",
                        new ColossalRoute(defaultFetchClient, feedStore),
                        "Colossal · 当代艺术、手工艺、摄影与视觉文化精选（英文）",
                        "艺术影像", "官方 RSS",
                        "www.thisiscolossal.com/feed/；无需密钥，只读取列表；建议每 360 分钟刷新；完整图片与作品请前往原文", 360),
                new Route("/aperture/articles",
                        new ApertureRoute(defaultFetchClient, feedStore),
                        "Aperture · 摄影作品、艺术家访谈与摄影书评论（英文）",
                        "艺术影像", "官方 RSS",
                        "aperture.org/feed/；无需密钥，只读取列表；建议每 1440 分钟刷新；公开文章摘要可订阅，数字档案可能需另行订阅", 1440),
                new Route("/dezeen/latest",
                        new DezeenRoute(defaultFetchClient, feedStore),
                        "Dezeen · 全球建筑、室内空间与设计项目报道（英文）",
                        "城市建筑", "官方 RSS",
                        "www.dezeen.com/feed/；无需密钥，只读取列表；建议每 180 分钟刷新；列表较大，建议低频刷新", 180),
                new Route("/archdaily/latest",
                        new ArchDailyRoute(defaultFetchClient, feedStore),
                        "ArchDaily · 全球建筑案例、城市空间与建筑行业观察（英文）",
                        "城市建筑", "官方 RSS",
                        "feeds.feedburner.com/Archdaily；无需密钥，只读取列表；建议每 180 分钟刷新；官网指定 FeedBurner 托管；含赞助内容", 180),
                new Route("/gcores/articles",
                        new GcoresRoute(defaultFetchClient, feedStore),
                        "机核 · 游戏文化、社区创作与活动资讯",
                        "游戏文化", "官方 RSS",
                        "www.gcores.com/rss；无需密钥，只读取列表；含社区文章与活动资讯；建议每 120 分钟刷新", 120),
                new Route("/yystv/articles",
                        new YystvRoute(defaultFetchClient, feedStore),
                        "游研社 · 游戏报道、行业故事与文化观察",
                        "游戏文化", "官方 RSS",
                        "www.yystv.cn/rss/feed；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                // ── tech blogs ────────────────────────────────────────────────
                new Route("/techcrunch",
                        new TechCrunchRoute(defaultFetchClient),
                        "TechCrunch latest articles",
                        "技术博客", "RSS 代理",
                        "代理 techcrunch.com/feed/ 官方 RSS 2.0，标准字段解析"),
                new Route("/wired/latest",
                        new WiredRoute(defaultFetchClient),
                        "WIRED latest stories",
                        "技术博客", "RSS 代理",
                        "代理 wired.com/feed/rss 官方 RSS 2.0，标准字段解析"),
                new Route("/mritd/blog",
                        new MritdRoute(defaultFetchClient),
                        "mritd 技术博客",
                        "技术博客", "RSS 代理",
                        "代理 mritd.com/feed.xml 博客 RSS/Atom，标准字段解析"),
                new Route("/microsoft/devops",
                        new MsDevOpsRoute(defaultFetchClient),
                        "Microsoft DevOps Blog",
                        "技术博客", "RSS 代理",
                        "代理 devblogs.microsoft.com/devops/feed/ RSS 2.0，标准字段解析"),
                new Route("/diygod/blog",
                        new DiygodRoute(defaultFetchClient),
                        "DIYGod 个人博客",
                        "技术博客", "RSS 代理",
                        "代理 diygod.cc/feed RSS/Atom，标准字段解析"),
                // ── programming ───────────────────────────────────────────────
                new Route("/github/releases/:owner/:repo",
                        new GithubReleaseRoute(defaultFetchClient, objectMapper),
                        "GitHub releases for any public repository — path params: :owner, :repo",
                        "编程", "JSON API",
                        "调用 GitHub REST /repos/:owner/:repo/releases，取版本号(tag_name/name)、release notes(body)、发布时间(published_at)"),
                new Route("/hn/:feed",
                        new HackerNewsRoute(defaultFetchClient),
                        "Hacker News feed — :feed can be top, new, best, ask, show, or job",
                        "编程", "HTML 解析",
                        "解析 news.ycombinator.com 页面 .athing 行：标题(.titleline>a)、来源域名(.sitestr)、作者(.hnuser)、发布时间(.age[title] ISO)、得分(.score)、评论数；单次请求"),
                new Route("/cnblogs/post",
                        new CnblogsRoute(defaultFetchClient),
                        "博客园 (cnblogs) featured blog posts",
                        "编程", "RSS 代理",
                        "代理 feed.cnblogs.com/blog/sitehome/rss 精华博文 RSS 2.0，标准字段解析"),
                // ── academic ──────────────────────────────────────────────────
                new Route("/arxiv/:category",
                        new ArxivRoute(defaultFetchClient),
                        "arXiv preprints for a subject category — path param: :category (e.g. cs.AI, cs.LG)",
                        "学术", "RSS 代理",
                        "代理 rss.arxiv.org/rss/:category 官方 RSS 2.0，标准字段解析"),
                // ── media ─────────────────────────────────────────────────────
                new Route("/bbc/news/:channel",
                        new BBCNewsRoute(defaultFetchClient, objectMapper, cacheService),
                        "BBC News 全文 — :channel 如 world、technology、world-asia、chinese、traditionalchinese",
                        "英文媒体", "RSS + 全文",
                        "从 BBC RSS 获取列表，再从每篇文章页 __NEXT_DATA__/__INITIAL_DATA__ 提取全文 block 树并渲染 HTML；支持中文频道(chinese/traditionalchinese)；详情页用 CacheService 缓存"),
                new Route("/bbc/learningenglish/:channel",
                        new BbcLearningEnglishRoute(defaultFetchClient, cacheService),
                        "BBC英语学习 — :channel 如 take-away-english、media-english、lingohack",
                        "英文媒体", "HTML 解析",
                        "抓取 bbc.co.uk/learningenglish/chinese/features/:channel 列表，逐篇从 .widget-richtext 提取全文；详情页 CacheService 缓存"),
                // ── Chinese media ─────────────────────────────────────────────
                new Route("/sspai/articles",
                        new SspaiRoute(defaultFetchClient),
                        "少数派 (sspai) latest articles",
                        "中文媒体", "RSS 代理",
                        "代理 sspai.com/feed 官方 RSS/Atom，标准字段解析"),
                new Route("/36kr/news",
                        new ThirtySixKrRoute(defaultFetchClient),
                        "36氪 latest tech news",
                        "中文媒体", "RSS 代理",
                        "代理 36kr.com/feed 官方 RSS/Atom，标准字段解析"),
                new Route("/huanqiu/news",
                        new HuanqiuRoute(defaultFetchClient),
                        "环球网最新新闻",
                        "中文媒体", "HTML 解析",
                        "解析 huanqiu.com 首页三区块：轮播 #foucsBoxCC li、主列表 .secNewsList p.listp、副列表 .thrNewsList dt/dd；仅取标题和链接，无发布时间"),
                new Route("/huanqiu/tech/:section",
                        new HuanqiuTechRoute(defaultFetchClient),
                        "环球网科技频道子栏目 — :section 例如 original(人工智能)、it、internet、automobile",
                        "中文媒体", "HTML 解析",
                        "解析 tech.huanqiu.com/:section，从 .data-container .item 的 textarea 元素取 item-aid、item-title、item-cnf-host、item-time；URL 拼接为 https://{host}/article/{aid}"),
                new Route("/xinhua/news",
                        new XinhuaRoute(defaultFetchClient),
                        "新华网最新新闻",
                        "中文媒体", "HTML 解析",
                        "解析 news.cn 首页三区块：快讯 #latest ul li a、深度 #depth ul li a、文字列表 .list-txt ul li a；发布时间从 URL 路径正则 /yyyymmdd/ 提取"),
                new Route("/cctv/news/:category",
                        new CctvNewsRoute(defaultFetchClient, cacheService, objectMapper),
                        "央视新闻各分类 — :category 可选值: news/china/world/society/law/ent/tech/life/edu",
                        "中文媒体", "JSONP API",
                        "调用 news.cctv.com JSONP API，按条目 ID 前缀分发：ART→文章全文(getXinwenNextArticleInfo)、PHO→图片集(contentinfo)、VIDE→HLS 视频(getHttpVideoInfo)；发布时间从 focus_date 字段解析"),
                // ── social / product ──────────────────────────────────────────
                new Route("/telegram/scitech_fans",
                        new ScitechFansChannelRoute(defaultFetchClient),
                        "科技爱好者周刊 · 阮一峰周刊的授权 TG 分发频道，开发工具与技术思考（中文）",
                        "TG 科技", "HTML 解析",
                        "t.me/s/scitech_fans 公开预览；无需登录；只读最新一页的文字与链接预览；保留帖子永久链接；建议每日更新", 1440),
                new Route("/telegram/newlearner",
                        new NewlearnerChannelRoute(defaultFetchClient),
                        "Newlearner 自留地 · 科技资讯、效率工具与开发者数字生活（中文）",
                        "TG 科技", "HTML 解析",
                        "t.me/s/NewlearnerChannel 公开预览；无需登录；只读最新一页；保留频道署名与帖子链接；建议每 120 分钟更新", 120),
                new Route("/telegram/solidot",
                        new SolidotChannelRoute(defaultFetchClient),
                        "Solidot TG · 科技、开源与科学资讯（中文，非官方 RSS 转发频道）",
                        "TG 科技", "HTML 解析",
                        "t.me/s/solidot 公开预览；无需登录；内容与 /solidot/news 有重合，可按阅读习惯二选一；建议每 120 分钟更新", 120),
                new Route("/telegram/channel/:channel",
                        new TelegramChannelRoute(defaultFetchClient),
                        "Telegram 公开频道 — :channel 为频道用户名，不含 @；仅支持有公开网页预览的频道",
                        "TG 科技", "HTML 解析",
                        "读取 t.me/s/:channel 最新一页；无需 Bot Token 或登录；不读取私有频道及仅有媒体的帖子", 120),
                new Route("/x/openai",
                        new OpenAiTwitterRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                        "OpenAI X · 官方模型、API 与开发工具动态（英文；需配置 X Cookie）",
                        "X 科技", "GraphQL API",
                        "x.com/OpenAI；在凭据配置中设置登录 Cookie（auth_token + ct0）；仅抓取最新一页；建议每 180 分钟更新", 180),
                new Route("/x/anthropic",
                        new AnthropicTwitterRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                        "Anthropic X · Claude、研究与开发工具官方动态（英文；需配置 X Cookie）",
                        "X 科技", "GraphQL API",
                        "x.com/AnthropicAI；复用 X 登录 Cookie；仅抓取最新一页；建议每 180 分钟更新", 180),
                new Route("/x/huggingface",
                        new HuggingFaceTwitterRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                        "Hugging Face X · 开放模型、数据集与开源 AI 工具（英文；需配置 X Cookie）",
                        "X 科技", "GraphQL API",
                        "x.com/huggingface；复用 X 登录 Cookie；仅抓取最新一页；建议每 180 分钟更新", 180),
                new Route("/x/karpathy",
                        new KarpathyTwitterRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                        "Andrej Karpathy · AI 技术解读、模型训练与编程实践（英文；需配置 X Cookie）",
                        "X 科技", "GraphQL API",
                        "x.com/karpathy；复用 X 登录 Cookie；个人观点；仅抓取最新一页；建议每 360 分钟更新", 360),
                new Route("/x/simonw",
                        new SimonWillisonTwitterRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                        "Simon Willison · LLM 应用、AI 编程与开源实践（英文；需配置 X Cookie）",
                        "X 科技", "GraphQL API",
                        "x.com/simonw；复用 X 登录 Cookie；个人观点；仅抓取最新一页；建议每 180 分钟更新", 180),
                new Route("/x/github",
                        new GithubTwitterRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                        "GitHub X · 开源生态、开发工具与平台官方动态（英文；需配置 X Cookie）",
                        "X 科技", "GraphQL API",
                        "x.com/github；复用 X 登录 Cookie；仅抓取最新一页；建议每 360 分钟更新", 360),
                new Route("/twitter/user/:id",
                        new TwitterUserRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                        "Twitter/X 用户时间线 — :id 为用户名(如 elonmusk)或 +数字ID(如 +44196397)",
                        "社区", "GraphQL API",
                        "调用 X GraphQL /i/api/graphql/{queryId}/UserTweets；GQL query ID 从 Twitter JS 动态解析并缓存 24h，失败时回退 hardcoded fallback；需 TWITTER_COOKIE(含 auth_token+ct0)"),
                new Route("/producthunt/daily",
                        new ProductHuntRoute(defaultFetchClient),
                        "Product Hunt daily featured products",
                        "社区", "RSS 代理",
                        "代理 producthunt.com/feed?category=undefined 官方 RSS 2.0，标准字段解析"),
                // ── video ─────────────────────────────────────────────────────
                new Route("/bilibili/user/video/:uid",
                        new BilibiliVideoRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                        "Bilibili UP主最新投稿 — :uid 为用户 UID",
                        "视频", "JSON API (WBI)",
                        "调用 WBI 签名端点 /x/space/wbi/arc/search；从 vlist 取 bvid、封面(pic)、标题、简介、作者、发布时间(UNIX秒)；需 BILIBILI_COOKIE"),
                new Route("/bilibili/user/dynamic/:uid",
                        new BilibiliDynamicRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                        "Bilibili UP主动态 — :uid 为用户 UID",
                        "视频", "JSON API",
                        "调用 /x/polymer/web-dynamic/v1/feed/space；按 type 分发：AV→视频封面+简介、DRAW→图文、WORD→纯文字、ARTICLE/OPUS→文章、FORWARD→转发；话题提取为 categories；需 BILIBILI_COOKIE"),
                new Route("/bilibili/followings/dynamic/:uid",
                        new BilibiliFollowingsDynamicRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                        "Bilibili 关注全部动态 — :uid 为登录用户自身 UID",
                        "视频", "JSON API",
                        "调用 dynamic_svr/dynamic_new?type_list=268435455；:uid 为已登录用户 UID；需 BILIBILI_COOKIE_{uid}(完整 Cookie)；解析视频/图文/文字/专栏/转发"),
                new Route("/bilibili/followings/video/:uid",
                        new BilibiliFollowingsVideoRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                        "Bilibili 关注视频动态 — :uid 为登录用户自身 UID",
                        "视频", "JSON API",
                        "调用 dynamic_svr/dynamic_new?type=8；:uid 为已登录用户 UID；需 BILIBILI_COOKIE_{uid}(SESSDATA 即可)；返回封面+简介"),
                new Route("/bilibili/followings/article/:uid",
                        new BilibiliFollowingsArticleRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                        "Bilibili 关注专栏动态 — :uid 为登录用户自身 UID",
                        "视频", "JSON API",
                        "调用 dynamic_svr/dynamic_new?type=64；:uid 为已登录用户 UID；需 BILIBILI_COOKIE_{uid}(SESSDATA 即可)；返回封面+摘要"),
                // ── generic ───────────────────────────────────────────────────
                new Route("/rss",
                        new GenericRssRoute(defaultFetchClient, feedStore),
                        "Generic RSS 2.0 / Atom feed — set feedUrl in route config to point at any feed",
                        "通用", "通用 RSS",
                        "代理路由配置中 feedUrl 指定的任意外部地址，自动识别 RSS 2.0 / Atom 格式并标准化")));
        this.routeConfigStore = new RouteConfigStore(configPath(), objectMapper);
    }

    public AppRuntime(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    /**
     * Builds a runtime from supplied collaborators for focused unit tests.
     *
     * @param cacheService     cache service used by resources and routes
     * @param routeRegistry    explicit route registry
     * @param routeConfigStore persisted route configuration store
     */
    AppRuntime(CacheService cacheService, RouteRegistry routeRegistry, RouteConfigStore routeConfigStore) {
        this.cacheService = cacheService;
        this.routeRegistry = routeRegistry;
        this.routeConfigStore = routeConfigStore;
        this.credentialConfigStore = new CredentialConfigStore(
                Path.of("build/test-credential-config.json"),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /**
     * Returns the shared cache service.
     *
     * @return cache service instance
     */
    public CacheService cacheService() {
        return cacheService;
    }

    public FetchClient fetchClient() {
        return defaultFetchClient;
    }

    public FeedFetchCoordinator fetchCoordinator() {
        return fetchCoordinator;
    }

    /**
     * Returns the explicit registry of feed routes.
     *
     * @return route registry instance
     */
    public RouteRegistry routeRegistry() {
        return routeRegistry;
    }

    /**
     * Returns the route configuration store.
     *
     * @return route configuration store instance
     */
    public RouteConfigStore routeConfigStore() {
        return routeConfigStore;
    }

    /**
     * Preserves explicit mounts while resolving concrete parameters from registered patterns.
     */
    public RouteMatch resolveRoute(String path, String sourcePath) {
        if (!path.equals(sourcePath) && sourcePath != null && !sourcePath.contains(":")) {
            return routeRegistry.resolve(sourcePath);
        }
        try {
            return routeRegistry.resolve(path);
        } catch (RouteException e) {
            if (e.error() != RouteError.ROUTE_NOT_FOUND) throw e;
            return routeRegistry.resolve(sourcePath);
        }
    }

    public String rssFeedUrl(RouteConfig config) {
        RouteMatch match = resolveRoute(config.path(), config.sourcePath());
        if (!(match.handler() instanceof GenericRssRoute rss)) return null;
        return rss.feedUrl(new RouteContext(config.path(), match.context().pathParams(), Map.of(),
                Duration.ofSeconds(config.detailCacheTtlSeconds()), config.feedUrl()));
    }

    public CredentialConfigStore credentialConfigStore() {
        return credentialConfigStore;
    }

    /**
     * Resolves the route configuration file path from a system property.
     *
     * @return configured route config path or the default local file
     */
    private Path configPath() {
        return Path.of(System.getProperty("fetch-news.route-config", "route-config.json"));
    }
}
