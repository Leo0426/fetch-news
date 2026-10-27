package app;

import app.core.CacheService;
import app.core.CredentialConfigStore;
import app.core.FetchClient;
import app.core.Route;
import app.core.Topic;
import app.routes.ArxivRoute;
import app.routes.CctvNewsRoute;
import app.routes.CnblogsRoute;
import app.routes.DiygodRoute;
import app.routes.GenericRssRoute;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Every built-in route, registered explicitly in one place: the curated catalog first, then the
 * hand-written sources. Registration order is matching order.
 */
final class BuiltInRoutes {
    private BuiltInRoutes() {}

    static List<Route> create(FetchClient defaultFetchClient, FeedStore feedStore, ObjectMapper objectMapper,
                              CacheService cacheService, CredentialConfigStore credentialConfigStore) {
        List<Route> routes = new ArrayList<>(CuratedRoutes.create(defaultFetchClient, feedStore, objectMapper,
                cacheService, credentialConfigStore));
        routes.addAll(List.of(
                    // ── tech blogs ────────────────────────────────────────────────
                    new Route("/techcrunch",
                            new TechCrunchRoute(defaultFetchClient),
                            "TechCrunch latest articles",
                            Topic.TECH, "RSS 代理",
                            "代理 techcrunch.com/feed/ 官方 RSS 2.0，标准字段解析"),
                    new Route("/wired/latest",
                            new WiredRoute(defaultFetchClient),
                            "WIRED latest stories",
                            Topic.TECH, "RSS 代理",
                            "代理 wired.com/feed/rss 官方 RSS 2.0，标准字段解析"),
                    new Route("/mritd/blog",
                            new MritdRoute(defaultFetchClient),
                            "mritd 技术博客",
                            Topic.SOFTWARE, "RSS 代理",
                            "代理 mritd.com/feed.xml 博客 RSS/Atom，标准字段解析"),
                    new Route("/microsoft/devops",
                            new MsDevOpsRoute(defaultFetchClient),
                            "Microsoft DevOps Blog",
                            Topic.SOFTWARE, "RSS 代理",
                            "代理 devblogs.microsoft.com/devops/feed/ RSS 2.0，标准字段解析"),
                    new Route("/diygod/blog",
                            new DiygodRoute(defaultFetchClient),
                            "DIYGod 个人博客",
                            Topic.SOFTWARE, "RSS 代理",
                            "代理 diygod.cc/feed RSS/Atom，标准字段解析"),
                    // ── programming ───────────────────────────────────────────────
                    new Route("/github/releases/:owner/:repo",
                            new GithubReleaseRoute(defaultFetchClient, objectMapper),
                            "GitHub releases for any public repository — path params: :owner, :repo",
                            Topic.SOFTWARE, "JSON API",
                            "调用 GitHub REST /repos/:owner/:repo/releases，取版本号(tag_name/name)、release notes(body)、发布时间(published_at)"),
                    new Route("/hn/:feed",
                            new HackerNewsRoute(defaultFetchClient),
                            "Hacker News feed — :feed can be top, new, best, ask, show, or job",
                            Topic.TECH, "HTML 解析",
                            "解析 news.ycombinator.com 页面 .athing 行：标题(.titleline>a)、来源域名(.sitestr)、作者(.hnuser)、发布时间(.age[title] ISO)、得分(.score)、评论数；单次请求"),
                    new Route("/cnblogs/post",
                            new CnblogsRoute(defaultFetchClient),
                            "博客园 (cnblogs) featured blog posts",
                            Topic.SOFTWARE, "RSS 代理",
                            "代理 feed.cnblogs.com/blog/sitehome/rss 精华博文 RSS 2.0，标准字段解析"),
                    // ── academic ──────────────────────────────────────────────────
                    new Route("/arxiv/:category",
                            new ArxivRoute(defaultFetchClient),
                            "arXiv preprints for a subject category — path param: :category (e.g. cs.AI, cs.LG)",
                            Topic.SCIENCE, "RSS 代理",
                            "代理 rss.arxiv.org/rss/:category 官方 RSS 2.0，标准字段解析"),
                    // ── media ─────────────────────────────────────────────────────
                    new Route("/bbc/news/:channel",
                            new BBCNewsRoute(defaultFetchClient, objectMapper, cacheService),
                            "BBC News 全文 — :channel 如 world、technology、world-asia、chinese、traditionalchinese",
                            Topic.NEWS, "RSS + 全文",
                            "从 BBC RSS 获取列表，再从每篇文章页 __NEXT_DATA__/__INITIAL_DATA__ 提取全文 block 树并渲染 HTML；支持中文频道(chinese/traditionalchinese)；详情页用 CacheService 缓存"),
                    new Route("/bbc/learningenglish/:channel",
                            new BbcLearningEnglishRoute(defaultFetchClient, cacheService),
                            "BBC英语学习 — :channel 如 take-away-english、media-english、lingohack",
                            Topic.HUMANITIES, "HTML 解析",
                            "抓取 bbc.co.uk/learningenglish/chinese/features/:channel 列表，逐篇从 .widget-richtext 提取全文；详情页 CacheService 缓存"),
                    // ── Chinese media ─────────────────────────────────────────────
                    new Route("/sspai/articles",
                            new SspaiRoute(defaultFetchClient),
                            "少数派 (sspai) latest articles",
                            Topic.TECH, "RSS 代理",
                            "代理 sspai.com/feed 官方 RSS/Atom，标准字段解析"),
                    new Route("/36kr/news",
                            new ThirtySixKrRoute(defaultFetchClient),
                            "36氪 latest tech news",
                            Topic.TECH, "RSS 代理",
                            "代理 www.36kr.com/feed 官方 RSS/Atom，标准字段解析"),
                    new Route("/huanqiu/news",
                            new HuanqiuRoute(defaultFetchClient),
                            "环球网最新新闻",
                            Topic.NEWS, "HTML 解析",
                            "解析 huanqiu.com 首页三区块：轮播 #foucsBoxCC li、主列表 .secNewsList p.listp、副列表 .thrNewsList dt/dd；仅取标题和链接，无发布时间"),
                    new Route("/huanqiu/tech/:section",
                            new HuanqiuTechRoute(defaultFetchClient),
                            "环球网科技频道子栏目 — :section 例如 original(人工智能)、it、internet、automobile",
                            Topic.TECH, "HTML 解析",
                            "解析 tech.huanqiu.com/:section，从 .data-container .item 的 textarea 元素取 item-aid、item-title、item-cnf-host、item-time；URL 拼接为 https://{host}/article/{aid}"),
                    new Route("/xinhua/news",
                            new XinhuaRoute(defaultFetchClient),
                            "新华网最新新闻",
                            Topic.NEWS, "HTML 解析",
                            "解析 news.cn 首页三区块：快讯 #latest ul li a、深度 #depth ul li a、文字列表 .list-txt ul li a；发布时间从 URL 路径正则 /yyyymmdd/ 提取"),
                    new Route("/cctv/news/:category",
                            new CctvNewsRoute(defaultFetchClient, cacheService, objectMapper),
                            "央视新闻各分类 — :category 可选值: news/china/world/society/law/ent/tech/life/edu",
                            Topic.NEWS, "JSONP API",
                            "调用 news.cctv.com JSONP API，按条目 ID 前缀分发：ART→文章全文(getXinwenNextArticleInfo)、PHO→图片集(contentinfo)、VIDE→HLS 视频(getHttpVideoInfo)；发布时间从 focus_date 字段解析"),
                    // ── social / product ──────────────────────────────────────────
                    new Route("/telegram/scitech_fans",
                            new ScitechFansChannelRoute(defaultFetchClient),
                            "科技爱好者周刊 · 阮一峰周刊的授权 TG 分发频道，开发工具与技术思考（中文）",
                            Topic.TECH, "HTML 解析",
                            "t.me/s/scitech_fans 公开预览；无需登录；只读最新一页的文字与链接预览；保留帖子永久链接；建议每日更新", 1440),
                    new Route("/telegram/newlearner",
                            new NewlearnerChannelRoute(defaultFetchClient),
                            "Newlearner 自留地 · 科技资讯、效率工具与开发者数字生活（中文）",
                            Topic.TECH, "HTML 解析",
                            "t.me/s/NewlearnerChannel 公开预览；无需登录；只读最新一页；保留频道署名与帖子链接；建议每 120 分钟更新", 120),
                    new Route("/telegram/solidot",
                            new SolidotChannelRoute(defaultFetchClient),
                            "Solidot TG · 科技、开源与科学资讯（中文，非官方 RSS 转发频道）",
                            Topic.TECH, "HTML 解析",
                            "t.me/s/solidot 公开预览；无需登录；内容与 /solidot/news 有重合，可按阅读习惯二选一；建议每 120 分钟更新", 120),
                    new Route("/telegram/channel/:channel",
                            new TelegramChannelRoute(defaultFetchClient),
                            "Telegram 公开频道 — :channel 为频道用户名，不含 @；仅支持有公开网页预览的频道",
                            Topic.FOLLOWING, "HTML 解析",
                            "读取 t.me/s/:channel 最新一页；无需 Bot Token 或登录；不读取私有频道及仅有媒体的帖子", 120),
                    new Route("/x/openai",
                            new OpenAiTwitterRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                            "OpenAI X · 官方模型、API 与开发工具动态（英文；需配置 X Cookie）",
                            Topic.AI, "GraphQL API",
                            "x.com/OpenAI；在凭据配置中设置登录 Cookie（auth_token + ct0）；仅抓取最新一页；建议每 180 分钟更新", 180),
                    new Route("/x/anthropic",
                            new AnthropicTwitterRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                            "Anthropic X · Claude、研究与开发工具官方动态（英文；需配置 X Cookie）",
                            Topic.AI, "GraphQL API",
                            "x.com/AnthropicAI；复用 X 登录 Cookie；仅抓取最新一页；建议每 180 分钟更新", 180),
                    new Route("/x/huggingface",
                            new HuggingFaceTwitterRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                            "Hugging Face X · 开放模型、数据集与开源 AI 工具（英文；需配置 X Cookie）",
                            Topic.AI, "GraphQL API",
                            "x.com/huggingface；复用 X 登录 Cookie；仅抓取最新一页；建议每 180 分钟更新", 180),
                    new Route("/x/karpathy",
                            new KarpathyTwitterRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                            "Andrej Karpathy · AI 技术解读、模型训练与编程实践（英文；需配置 X Cookie）",
                            Topic.AI, "GraphQL API",
                            "x.com/karpathy；复用 X 登录 Cookie；个人观点；仅抓取最新一页；建议每 360 分钟更新", 360),
                    new Route("/x/simonw",
                            new SimonWillisonTwitterRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                            "Simon Willison · LLM 应用、AI 编程与开源实践（英文；需配置 X Cookie）",
                            Topic.AI, "GraphQL API",
                            "x.com/simonw；复用 X 登录 Cookie；个人观点；仅抓取最新一页；建议每 180 分钟更新", 180),
                    new Route("/x/github",
                            new GithubTwitterRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                            "GitHub X · 开源生态、开发工具与平台官方动态（英文；需配置 X Cookie）",
                            Topic.SOFTWARE, "GraphQL API",
                            "x.com/github；复用 X 登录 Cookie；仅抓取最新一页；建议每 360 分钟更新", 360),
                    new Route("/twitter/user/:id",
                            new TwitterUserRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                            "Twitter/X 用户时间线 — :id 为用户名(如 elonmusk)或 +数字ID(如 +44196397)",
                            Topic.FOLLOWING, "GraphQL API",
                            "调用 X GraphQL /i/api/graphql/{queryId}/UserTweets；GQL query ID 从 Twitter JS 动态解析并缓存 24h，失败时回退 hardcoded fallback；需 TWITTER_COOKIE(含 auth_token+ct0)"),
                    new Route("/producthunt/daily",
                            new ProductHuntRoute(defaultFetchClient),
                            "Product Hunt daily featured products",
                            Topic.TECH, "RSS 代理",
                            "代理 producthunt.com/feed?category=undefined 官方 RSS 2.0，标准字段解析"),
                    // ── video ─────────────────────────────────────────────────────
                    new Route("/bilibili/user/video/:uid",
                            new BilibiliVideoRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                            "Bilibili UP主最新投稿 — :uid 为用户 UID",
                            Topic.FOLLOWING, "JSON API (WBI)",
                            "调用 WBI 签名端点 /x/space/wbi/arc/search；从 vlist 取 bvid、封面(pic)、标题、简介、作者、发布时间(UNIX秒)；需 BILIBILI_COOKIE"),
                    new Route("/bilibili/user/dynamic/:uid",
                            new BilibiliDynamicRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                            "Bilibili UP主动态 — :uid 为用户 UID",
                            Topic.FOLLOWING, "JSON API",
                            "调用 /x/polymer/web-dynamic/v1/feed/space；按 type 分发：AV→视频封面+简介、DRAW→图文、WORD→纯文字、ARTICLE/OPUS→文章、FORWARD→转发；话题提取为 categories；需 BILIBILI_COOKIE"),
                    new Route("/bilibili/followings/dynamic/:uid",
                            new BilibiliFollowingsDynamicRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                            "Bilibili 关注全部动态 — :uid 为登录用户自身 UID",
                            Topic.FOLLOWING, "JSON API",
                            "调用 dynamic_svr/dynamic_new?type_list=268435455；:uid 为已登录用户 UID；需 BILIBILI_COOKIE_{uid}(完整 Cookie)；解析视频/图文/文字/专栏/转发"),
                    new Route("/bilibili/followings/video/:uid",
                            new BilibiliFollowingsVideoRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                            "Bilibili 关注视频动态 — :uid 为登录用户自身 UID",
                            Topic.FOLLOWING, "JSON API",
                            "调用 dynamic_svr/dynamic_new?type=8；:uid 为已登录用户 UID；需 BILIBILI_COOKIE_{uid}(SESSDATA 即可)；返回封面+简介"),
                    new Route("/bilibili/followings/article/:uid",
                            new BilibiliFollowingsArticleRoute(defaultFetchClient, objectMapper, cacheService, credentialConfigStore),
                            "Bilibili 关注专栏动态 — :uid 为登录用户自身 UID",
                            Topic.FOLLOWING, "JSON API",
                            "调用 dynamic_svr/dynamic_new?type=64；:uid 为已登录用户 UID；需 BILIBILI_COOKIE_{uid}(SESSDATA 即可)；返回封面+摘要"),
                    // ── generic ───────────────────────────────────────────────────
                    new Route("/rss",
                            new GenericRssRoute(defaultFetchClient, feedStore),
                            "Generic RSS 2.0 / Atom feed — set feedUrl in route config to point at any feed",
                            Topic.GENERAL, "通用 RSS",
                            "代理路由配置中 feedUrl 指定的任意外部地址，自动识别 RSS 2.0 / Atom 格式并标准化")));
        return List.copyOf(routes);
    }
}
