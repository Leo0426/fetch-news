package app;

import app.core.CacheService;
import app.core.DefaultFetchClient;
import app.core.FetchClient;
import app.core.Route;
import app.core.RouteConfigStore;
import app.core.RouteRegistry;
import app.routes.ArxivRoute;
import app.routes.GenericRssRoute;
import app.routes.CctvNewsRoute;
import app.routes.CnblogsRoute;
import app.routes.DiygodRoute;
import app.routes.HackerNewsRoute;
import app.routes.MritdRoute;
import app.routes.MsDevOpsRoute;
import app.routes.ProductHuntRoute;
import app.routes.SspaiRoute;
import app.routes.TechCrunchRoute;
import app.routes.ThirtySixKrRoute;
import app.routes.WiredRoute;
import app.routes.XinhuaRoute;
import app.routes.bbc.BBCNewsRoute;
import app.routes.bbc.BbcLearningEnglishRoute;
import app.routes.bilibili.BilibiliDynamicRoute;
import app.routes.bilibili.BilibiliFollowingsArticleRoute;
import app.routes.bilibili.BilibiliFollowingsDynamicRoute;
import app.routes.bilibili.BilibiliFollowingsVideoRoute;
import app.routes.bilibili.BilibiliVideoRoute;
import app.routes.github.GithubReleaseRoute;
import app.routes.huanqiu.HuanqiuRoute;
import app.routes.huanqiu.HuanqiuTechRoute;
import app.routes.twitter.TwitterUserRoute;
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
    private final CredentialConfigStore credentialConfigStore;

    /**
     * Builds the default runtime used by Spring injection.
     *
     * @param objectMapper JSON mapper shared with configuration persistence
     */
    @Autowired
    public AppRuntime(ObjectMapper objectMapper) {
        this.cacheService = new CacheService();
        this.credentialConfigStore = new CredentialConfigStore(
                Path.of("data/credential-config.json"), objectMapper);
        FetchClient defaultFetchClient = new DefaultFetchClient();
        this.routeRegistry = new RouteRegistry(List.of(
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
