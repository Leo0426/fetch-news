package app;

import app.core.CacheService;
import app.core.CredentialConfigStore;
import app.core.FetchClient;
import app.core.Route;
import app.core.Topic;
import app.routes.AeonMagazineRoute;
import app.routes.ApertureRoute;
import app.routes.ApsResearchNewsRoute;
import app.routes.ArchDailyRoute;
import app.routes.CarbonBriefRoute;
import app.routes.ChinanewsCultureRoute;
import app.routes.ChinanewsFinanceRoute;
import app.routes.ChinanewsSocietyRoute;
import app.routes.ColossalRoute;
import app.routes.DezeenRoute;
import app.routes.EcbBlogRoute;
import app.routes.EdSurgeNewsRoute;
import app.routes.FactorioBlogRoute;
import app.routes.FedsNotesRoute;
import app.routes.FtChineseRoute;
import app.routes.GcoresRoute;
import app.routes.GodotNewsRoute;
import app.routes.GuardianWorldRoute;
import app.routes.HechingerReportRoute;
import app.routes.HuxiuRoute;
import app.routes.JstorDailyRoute;
import app.routes.KffHealthNewsRoute;
import app.routes.NasaNewsRoute;
import app.routes.NoaaNewsRoute;
import app.routes.NprWorldRoute;
import app.routes.NytChineseRoute;
import app.routes.OurWorldInDataRoute;
import app.routes.PewResearchRoute;
import app.routes.PsycheMagazineRoute;
import app.routes.PublicDomainReviewRoute;
import app.routes.QuantaMagazineRoute;
import app.routes.RthkLocalRoute;
import app.routes.ScienceNewsRoute;
import app.routes.SmashingMagazineRoute;
import app.routes.StatNewsRoute;
import app.routes.ThirtySixKrNewsflashRoute;
import app.routes.TmtpostRoute;
import app.routes.UnNewsChineseRoute;
import app.routes.WoshipmRoute;
import app.routes.YystvRoute;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * The curated subscription catalog: verified publisher RSS/Atom feeds offered in /index#subscriptions.
 * Technology feeds live in {@link CuratedTechRoutes}; this class lists the rest and returns both.
 */
final class CuratedRoutes {
    private CuratedRoutes() {}

    static List<Route> create(FetchClient defaultFetchClient, FeedStore feedStore, ObjectMapper objectMapper,
                              CacheService cacheService, CredentialConfigStore credentialConfigStore) {
        List<Route> routes = new ArrayList<>(CuratedTechRoutes.create(defaultFetchClient, feedStore, objectMapper,
                cacheService, credentialConfigStore));
        routes.addAll(List.of(
                    new Route("/chinanews/society",
                            new ChinanewsSocietyRoute(defaultFetchClient, feedStore),
                            "中新网社会 · 大陆社会与民生",
                            Topic.NEWS, "官方 RSS",
                            "chinanews.com.cn/rss/society.xml；无需密钥，只读取列表；建议每 30 分钟刷新", 30),
                    new Route("/un/news/zh",
                            new UnNewsChineseRoute(defaultFetchClient, feedStore),
                            "联合国新闻中文 · 人道、健康、气候与公共议题",
                            Topic.NEWS, "官方 RSS",
                            "news.un.org 中文 RSS；无需密钥，只读取列表；建议每 60 分钟刷新", 60),
                    new Route("/rthk/local",
                            new RthkLocalRoute(defaultFetchClient, feedStore),
                            "香港电台本地 · 社会与公共政策（繁体）",
                            Topic.NEWS, "官方 RSS",
                            "rthk.hk 官方本地新闻 RSS；无需密钥，只读取列表；建议每 30 分钟刷新", 30),
                    new Route("/guardian/world",
                            new GuardianWorldRoute(defaultFetchClient, feedStore),
                            "The Guardian 世界 · 国际与社会新闻（英文）",
                            Topic.NEWS, "官方 RSS",
                            "theguardian.com/world/rss；无需密钥，只读取列表；建议每 60 分钟刷新", 60),
                    new Route("/npr/world",
                            new NprWorldRoute(defaultFetchClient, feedStore),
                            "NPR 国际 · 美国公共广播国际新闻（英文）",
                            Topic.NEWS, "官方 RSS",
                            "feeds.npr.org/1004/rss.xml；无需密钥，只读取列表；建议每 60 分钟刷新", 60),
                    new Route("/nytimes/cn",
                            new NytChineseRoute(defaultFetchClient, feedStore),
                            "纽约时报中文网 · 国际、中国与商业报道",
                            Topic.NEWS, "官方 RSS",
                            "cn.nytimes.com/rss/；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/nasa/news",
                            new NasaNewsRoute(defaultFetchClient, feedStore),
                            "NASA · 航天任务与科学动态（英文）",
                            Topic.SCIENCE, "官方 RSS",
                            "www.nasa.gov/feed/；无需密钥，只读取列表；建议每 180 分钟刷新", 180),
                    new Route("/quanta/magazine",
                            new QuantaMagazineRoute(defaultFetchClient, feedStore),
                            "Quanta Magazine · 数学与基础科学深度报道（英文）",
                            Topic.SCIENCE, "官方 RSS",
                            "www.quantamagazine.org/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/sciencenews/latest",
                            new ScienceNewsRoute(defaultFetchClient, feedStore),
                            "Science News · 综合科学新闻（英文摘要）",
                            Topic.SCIENCE, "官方 RSS",
                            "www.sciencenews.org/feed；无需密钥，只读取列表；建议每 180 分钟刷新；官网全文可能有阅读额度或订阅限制", 180),
                    new Route("/carbonbrief/news",
                            new CarbonBriefRoute(defaultFetchClient, feedStore),
                            "Carbon Brief · 气候、能源与政策分析（英文）",
                            Topic.SCIENCE, "官方 RSS",
                            "www.carbonbrief.org/feed；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/noaa/news",
                            new NoaaNewsRoute(defaultFetchClient, feedStore),
                            "NOAA · 海洋、天气与气候机构动态（英文）",
                            Topic.SCIENCE, "官方 RSS",
                            "www.noaa.gov/rss.xml；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/ecb/blog",
                            new EcbBlogRoute(defaultFetchClient, feedStore),
                            "欧洲央行博客 · 欧元区宏观与货币政策（英文）",
                            Topic.BUSINESS, "官方 RSS",
                            "www.ecb.europa.eu/rss/blog.html；无需密钥，只读取列表；建议每 120 分钟刷新；上游提供标题、日期与原文链接，不含摘要", 120),
                    new Route("/federalreserve/feds-notes",
                            new FedsNotesRoute(defaultFetchClient, feedStore),
                            "美联储 FEDS Notes · 经济金融研究札记（英文）",
                            Topic.BUSINESS, "官方 RSS",
                            "www.federalreserve.gov/feeds/feds_notes.xml；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/chinanews/finance",
                            new ChinanewsFinanceRoute(defaultFetchClient, feedStore),
                            "中新网财经 · 国内经济与产业资讯",
                            Topic.BUSINESS, "官方 RSS",
                            "www.chinanews.com.cn/rss/finance.xml；无需密钥，只读取列表；建议每 60 分钟刷新", 60),
                    new Route("/ftchinese/news",
                            new FtChineseRoute(defaultFetchClient, feedStore),
                            "FT 中文网 · 全球财经、商业与国际时事",
                            Topic.BUSINESS, "官方 RSS",
                            "www.ftchinese.com/rss/news；无需密钥，只读取列表；部分原文需订阅；建议每 120 分钟刷新", 120),
                    new Route("/huxiu/articles",
                            new HuxiuRoute(defaultFetchClient, feedStore),
                            "虎嗅 · 科技商业深度报道与评论",
                            Topic.BUSINESS, "官方 RSS",
                            "rss.huxiu.com；无需密钥，只读取列表；含正文全文，单次约 3 MB；建议每 120 分钟刷新", 120),
                    new Route("/tmtpost/news",
                            new TmtpostRoute(defaultFetchClient, feedStore),
                            "钛媒体 · 科技、财经与产业新闻",
                            Topic.BUSINESS, "官方 RSS",
                            "www.tmtpost.com/rss.xml；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/woshipm/articles",
                            new WoshipmRoute(defaultFetchClient, feedStore),
                            "人人都是产品经理 · 产品设计、运营与 AI 产品实践",
                            Topic.BUSINESS, "官方 RSS",
                            "www.woshipm.com/feed；无需密钥，只读取列表；含正文全文；建议每 360 分钟刷新", 360),
                    new Route("/36kr/newsflashes",
                            new ThirtySixKrNewsflashRoute(defaultFetchClient, feedStore),
                            "36氪快讯 · 创投、公司与市场即时快讯",
                            Topic.BUSINESS, "官方 RSS",
                            "www.36kr.com/feed-newsflash；无需密钥，只读取列表；更新频繁；建议每 60 分钟刷新", 60),
                    new Route("/smashing/articles",
                            new SmashingMagazineRoute(defaultFetchClient, feedStore),
                            "Smashing Magazine · 网页设计、UX 与可访问性（英文）",
                            Topic.DESIGN, "官方 RSS",
                            "www.smashingmagazine.com/feed/；无需密钥，只读取列表；建议每 180 分钟刷新；列表响应较大，建议低频刷新", 180),
                    new Route("/aeon/essays",
                            new AeonMagazineRoute(defaultFetchClient, feedStore),
                            "Aeon · 哲学、心理与人文深度文章（英文）",
                            Topic.HUMANITIES, "官方 RSS",
                            "aeon.co/feed.rss；无需密钥，只读取列表；建议每 120 分钟刷新；RSS 供个人非商业阅读，保留原文署名与链接", 120),
                    new Route("/chinanews/culture",
                            new ChinanewsCultureRoute(defaultFetchClient, feedStore),
                            "中新网文化 · 文化、艺术与历史资讯",
                            Topic.HUMANITIES, "官方 RSS",
                            "www.chinanews.com.cn/rss/culture.xml；无需密钥，只读取列表；建议每 120 分钟刷新", 120),
                    new Route("/kff/health-news",
                            new KffHealthNewsRoute(defaultFetchClient, feedStore),
                            "KFF Health News · 公共健康、医疗制度与健康政策深度报道（英文）",
                            Topic.HEALTH, "官方 RSS",
                            "kffhealthnews.org/feed/；无需密钥，只读取列表；建议每 360 分钟刷新；主要报道美国公共健康与医疗制度", 360),
                    new Route("/stat/news",
                            new StatNewsRoute(defaultFetchClient, feedStore),
                            "STAT · 医学、生命科学与医疗行业新闻（英文；部分原文需订阅）",
                            Topic.HEALTH, "官方 RSS",
                            "www.statnews.com/feed/；无需密钥，只读取列表；建议每 180 分钟刷新；部分原文需 STAT+ 订阅", 180),
                    new Route("/psyche/magazine",
                            new PsycheMagazineRoute(defaultFetchClient, feedStore),
                            "Psyche · 心理、认知与自我理解科普（英文）",
                            Topic.HUMANITIES, "官方 RSS",
                            "psyche.co/feed.rss；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/aps/research",
                            new ApsResearchNewsRoute(defaultFetchClient, feedStore),
                            "APS 心理科学研究 · 心理学研究发布与学术进展（英文）",
                            Topic.SCIENCE, "官方 RSS",
                            "www.psychologicalscience.org/news/releases/feed；无需密钥，只读取列表；建议每 720 分钟刷新", 720),
                    new Route("/edsurge/news",
                            new EdSurgeNewsRoute(defaultFetchClient, feedStore),
                            "EdSurge · 教育创新、教学实践与教育科技报道（英文）",
                            Topic.HUMANITIES, "官方 RSS",
                            "www.edsurge.com/articles_rss；无需密钥，只读取列表；建议每 360 分钟刷新；主要覆盖美国教育议题", 360),
                    new Route("/hechinger/report",
                            new HechingerReportRoute(defaultFetchClient, feedStore),
                            "The Hechinger Report · 教育公平、学习成效与学校创新深度报道（英文）",
                            Topic.HUMANITIES, "官方 RSS",
                            "hechingerreport.org/feed/；无需密钥，只读取列表；建议每 360 分钟刷新；主要覆盖美国教育议题", 360),
                    new Route("/ourworldindata/articles",
                            new OurWorldInDataRoute(defaultFetchClient, feedStore),
                            "Our World in Data · 人口、健康与全球发展数据研究（英文）",
                            Topic.HUMANITIES, "官方 RSS",
                            "ourworldindata.org/atom.xml；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/pewresearch/publications",
                            new PewResearchRoute(defaultFetchClient, feedStore),
                            "Pew Research Center · 民意调查、人口与社会趋势研究（英文）",
                            Topic.HUMANITIES, "官方 RSS",
                            "www.pewresearch.org/publications/feed/；无需密钥，只读取列表；建议每 360 分钟刷新；含报告章节，提供摘要", 360),
                    new Route("/godot/news",
                            new GodotNewsRoute(defaultFetchClient, feedStore),
                            "Godot Engine · 游戏引擎开发进展、技术文章与版本发布（英文）",
                            Topic.GAMES, "官方 RSS",
                            "godotengine.org/atom.xml；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/factorio/blog",
                            new FactorioBlogRoute(defaultFetchClient, feedStore),
                            "Factorio Friday Facts · 工厂游戏开发日志、性能优化与设计实践（英文）",
                            Topic.GAMES, "官方 RSS",
                            "factorio.com/blog/rss；无需密钥，只读取列表；建议每 720 分钟刷新；开发日志发布频率不固定", 720),
                    new Route("/jstor/daily",
                            new JstorDailyRoute(defaultFetchClient, feedStore),
                            "JSTOR Daily · 以学术研究解读历史、文化与公共议题（英文）",
                            Topic.HUMANITIES, "官方 RSS",
                            "daily.jstor.org/feed/；无需密钥，只读取列表；建议每 360 分钟刷新", 360),
                    new Route("/public-domain-review/latest",
                            new PublicDomainReviewRoute(defaultFetchClient, feedStore),
                            "公共领域评论 · 探索历史艺术、文学与思想中的公共领域珍藏（英文）",
                            Topic.HUMANITIES, "官方 RSS",
                            "publicdomainreview.org/rss.xml；无需密钥，只读取列表；建议每 1440 分钟刷新；以摘要为主，适合低频阅读", 1440),
                    new Route("/colossal/latest",
                            new ColossalRoute(defaultFetchClient, feedStore),
                            "Colossal · 当代艺术、手工艺、摄影与视觉文化精选（英文）",
                            Topic.DESIGN, "官方 RSS",
                            "www.thisiscolossal.com/feed/；无需密钥，只读取列表；建议每 360 分钟刷新；完整图片与作品请前往原文", 360),
                    new Route("/aperture/articles",
                            new ApertureRoute(defaultFetchClient, feedStore),
                            "Aperture · 摄影作品、艺术家访谈与摄影书评论（英文）",
                            Topic.DESIGN, "官方 RSS",
                            "aperture.org/feed/；无需密钥，只读取列表；建议每 1440 分钟刷新；公开文章摘要可订阅，数字档案可能需另行订阅", 1440),
                    new Route("/dezeen/latest",
                            new DezeenRoute(defaultFetchClient, feedStore),
                            "Dezeen · 全球建筑、室内空间与设计项目报道（英文）",
                            Topic.DESIGN, "官方 RSS",
                            "www.dezeen.com/feed/；无需密钥，只读取列表；建议每 180 分钟刷新；列表较大，建议低频刷新", 180),
                    new Route("/archdaily/latest",
                            new ArchDailyRoute(defaultFetchClient, feedStore),
                            "ArchDaily · 全球建筑案例、城市空间与建筑行业观察（英文）",
                            Topic.DESIGN, "官方 RSS",
                            "feeds.feedburner.com/Archdaily；无需密钥，只读取列表；建议每 180 分钟刷新；官网指定 FeedBurner 托管；含赞助内容", 180),
                    new Route("/gcores/articles",
                            new GcoresRoute(defaultFetchClient, feedStore),
                            "机核 · 游戏文化、社区创作与活动资讯",
                            Topic.GAMES, "官方 RSS",
                            "www.gcores.com/rss；无需密钥，只读取列表；含社区文章与活动资讯；建议每 120 分钟刷新", 120),
                    new Route("/yystv/articles",
                            new YystvRoute(defaultFetchClient, feedStore),
                            "游研社 · 游戏报道、行业故事与文化观察",
                            Topic.GAMES, "官方 RSS",
                            "www.yystv.cn/rss/feed；无需密钥，只读取列表；建议每 120 分钟刷新", 120)));
        return routes;
    }
}
