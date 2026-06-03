package app;

import app.core.CacheService;
import app.core.DefaultFetchClient;
import app.core.FetchClient;
import app.core.Route;
import app.core.RouteConfigStore;
import app.core.RouteRegistry;
import app.routes.ArxivRoute;
import app.routes.GenericRssRoute;
import app.routes.BBCNewsRoute;
import app.routes.BilibiliVideoRoute;
import app.routes.Cctv7Route;
import app.routes.CnblogsRoute;
import app.routes.DiygodRoute;
import app.routes.GithubIssuesRoute;
import app.routes.GithubReleaseRoute;
import app.routes.GithubStarsRoute;
import app.routes.HackerNewsRoute;
import app.routes.HuanqiuRoute;
import app.routes.HuanqiuTechRoute;
import app.routes.MritdRoute;
import app.routes.MsDevOpsRoute;
import app.routes.ProductHuntRoute;
import app.routes.RedditRoute;
import app.routes.SspaiRoute;
import app.routes.StackOverflowRoute;
import app.routes.TechCrunchRoute;
import app.routes.ThirtySixKrRoute;
import app.routes.WiredRoute;
import app.routes.XinhuaRoute;
import app.routes.ZedBlogRoute;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Owns the application-wide services and explicit route registration.
 */
@Component
public class AppRuntime {
    private final CacheService cacheService;
    private final RouteRegistry routeRegistry;
    private final RouteConfigStore routeConfigStore;

    /**
     * Builds the default runtime used by Spring injection.
     *
     * @param objectMapper JSON mapper shared with configuration persistence
     */
    @Autowired
    public AppRuntime(ObjectMapper objectMapper) {
        this.cacheService = new CacheService();
        FetchClient defaultFetchClient = new DefaultFetchClient();
        this.routeRegistry = new RouteRegistry(List.of(
                // ── tech blogs ────────────────────────────────────────────────
                new Route("/zed/blog",
                        new ZedBlogRoute(defaultFetchClient, cacheService),
                        "Latest posts from the Zed editor blog",
                        "技术博客", "HTML 解析",
                        "解析 zed.dev/blog 列表页，选 a[href^=/blog/]（需含 h2）卡片，取 h2 标题和 h2 后首段摘要；无发布时间"),
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
                        "调用 GitHub REST /repos/:owner/:repo/releases，取版本号(tag_name/name)、release notes(body)、发布时间(published_at)、作者；跳过草稿"),
                new Route("/github/issues/:owner/:repo",
                        new GithubIssuesRoute(defaultFetchClient, objectMapper),
                        "Open GitHub issues for any public repository — path params: :owner, :repo",
                        "编程", "JSON API",
                        "调用 GitHub REST /repos/:owner/:repo/issues?state=open，取标题、正文(截1000字)、标签(labels)、作者(user.login)、创建时间；跳过 PR"),
                new Route("/github/stars/:user",
                        new GithubStarsRoute(defaultFetchClient, objectMapper),
                        "Recently starred repositories by a GitHub user — path param: :user",
                        "编程", "JSON API",
                        "调用 GitHub REST /users/:user/starred?sort=created，取仓库名(full_name)、描述、主语言(language)、star数、最近推送时间(pushed_at)"),
                new Route("/hn/:feed",
                        new HackerNewsRoute(defaultFetchClient, cacheService, objectMapper),
                        "Hacker News feed — :feed can be top, new, best, ask, show, or job",
                        "编程", "JSON API",
                        "Firebase API 取 story ID 列表，逐条调 item API，取标题、链接、作者(by)、发布时间(UNIX秒)、得分(score)、评论数(descendants)；仅保留 type=story/job"),
                new Route("/stackoverflow/tag/:tag",
                        new StackOverflowRoute(defaultFetchClient),
                        "Stack Overflow questions for a tag — path param: :tag",
                        "编程", "RSS 代理",
                        "代理 stackoverflow.com/feeds/tag/:tag 官方 Atom，标准字段解析"),
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
                new Route("/bbc/news/:category",
                        new BBCNewsRoute(defaultFetchClient),
                        "BBC News RSS — :category can be world, technology, business, health, etc.",
                        "英文媒体", "RSS 代理",
                        "代理 feeds.bbci.co.uk/news/:category/rss.xml 官方 RSS 2.0，标准字段解析"),
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
                new Route("/cctv/7",
                        new Cctv7Route(defaultFetchClient),
                        "CCTV-7 国防军事频道最新节目",
                        "中文媒体", "HTML 解析",
                        "解析 tv.cctv.com/cctv7/ 首页轮播区 div.pindao19777_ind01 li.swiper-slide 和热门区 div.pindao19777_ind04 ul.con_list li；发布时间从 URL 路径 /yyyy/mm/dd/ 提取"),
                // ── social / product ──────────────────────────────────────────
                new Route("/reddit/r/:subreddit",
                        new RedditRoute(defaultFetchClient, objectMapper),
                        "Reddit hot posts for a subreddit — path param: :subreddit",
                        "社区", "JSON API",
                        "调用 reddit.com/r/:subreddit/hot.json，从 children[].data 取标题、链接/permalink、作者、得分(score)、评论数；自发帖附 selftext(截500字)"),
                new Route("/producthunt/daily",
                        new ProductHuntRoute(defaultFetchClient),
                        "Product Hunt daily featured products",
                        "社区", "RSS 代理",
                        "代理 producthunt.com/feed?category=undefined 官方 RSS 2.0，标准字段解析"),
                // ── video ─────────────────────────────────────────────────────
                new Route("/bilibili/user/video/:uid",
                        new BilibiliVideoRoute(defaultFetchClient, objectMapper),
                        "Bilibili UP主最新视频 — path param: :uid (用户 UID)",
                        "视频", "JSON API",
                        "调用 Bilibili space/arc/search API，从 vlist 数组取 bvid、标题、UP主名(author)、简介、发布时间(UNIX秒)；链接拼接 bilibili.com/video/:bvid"),
                // ── generic ───────────────────────────────────────────────────
                new Route("/rss",
                        new GenericRssRoute(defaultFetchClient),
                        "Generic RSS 2.0 / Atom feed — set feedUrl in route config to point at any feed",
                        "通用", "通用 RSS",
                        "代理路由配置中 feedUrl 指定的任意外部地址，自动识别 RSS 2.0 / Atom 格式并标准化")));
        this.routeConfigStore = new RouteConfigStore(configPath(), objectMapper);
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
    }

    /**
     * Returns the shared cache service.
     *
     * @return cache service instance
     */
    public CacheService cacheService() {
        return cacheService;
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
     * Resolves the route configuration file path from a system property.
     *
     * @return configured route config path or the default local file
     */
    private Path configPath() {
        return Path.of(System.getProperty("fetch-news.route-config", "route-config.json"));
    }
}
