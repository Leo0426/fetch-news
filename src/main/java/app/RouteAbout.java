package app;

import java.util.Map;

/**
 * Static per-route "about" blurbs rendered in the route edit modal.
 * Keys are source route paths; values are self-contained HTML snippets.
 */
class RouteAbout {

    static final Map<String, String> ABOUTS = Map.ofEntries(

        // ── tech blogs ─────────────────────────────────────────────────────────
        Map.entry("/techcrunch", """
            <p>TechCrunch 是全球最知名的科技媒体之一，专注科技行业动态、创业公司和风险投资。</p>
            <table class="about-table">
              <thead><tr><th>领域</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>AI</td><td>OpenAI、Anthropic、Google AI 等大模型动态</td></tr>
                <tr><td>Startup</td><td>创业公司融资与成长</td></tr>
                <tr><td>Venture Capital</td><td>风投动态与市场分析</td></tr>
                <tr><td>Security</td><td>网络安全事件与漏洞</td></tr>
                <tr><td>Cloud</td><td>云计算与基础设施</td></tr>
                <tr><td>Software</td><td>SaaS、开发工具</td></tr>
                <tr><td>Hardware</td><td>消费电子与芯片</td></tr>
                <tr><td>Robotics</td><td>机器人产业</td></tr>
                <tr><td>Space</td><td>航天科技</td></tr>
                <tr><td>Crypto</td><td>加密货币与区块链</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/wired/latest", """
            <p>WIRED 是美国知名科技文化杂志，以深度报道和前瞻视角著称，兼顾技术与人文。</p>
            <table class="about-table">
              <thead><tr><th>领域</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>Technology</td><td>科技趋势与创新</td></tr>
                <tr><td>AI</td><td>人工智能的社会影响</td></tr>
                <tr><td>Culture</td><td>科技对文化的渗透与改变</td></tr>
                <tr><td>Security</td><td>网络安全与隐私</td></tr>
                <tr><td>Science</td><td>前沿科学研究</td></tr>
                <tr><td>Politics</td><td>科技政策与监管</td></tr>
                <tr><td>Business</td><td>科技公司与商业模式</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/mritd/blog", """
            <p>mritd 是国内知名 DevOps / 云原生工程师的个人博客，内容深入实用，聚焦生产环境实践。</p>
            <table class="about-table">
              <thead><tr><th>领域</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>Kubernetes</td><td>K8s 集群配置、调优与运维</td></tr>
                <tr><td>Docker</td><td>容器化实践与镜像管理</td></tr>
                <tr><td>Linux</td><td>系统运维与性能优化</td></tr>
                <tr><td>CI/CD</td><td>自动化部署与流水线</td></tr>
                <tr><td>Network</td><td>网络配置、代理与安全</td></tr>
                <tr><td>Tools</td><td>运维工具链推荐与评测</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/microsoft/devops", """
            <p>Microsoft DevOps Blog 是微软工程师团队发布 Azure DevOps 和 GitHub 相关更新的官方博客。</p>
            <table class="about-table">
              <thead><tr><th>领域</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>Azure DevOps</td><td>Boards、Pipelines、Repos、Artifacts 更新</td></tr>
                <tr><td>GitHub</td><td>GitHub Actions、Copilot 集成动态</td></tr>
                <tr><td>Release Notes</td><td>功能发布与版本说明</td></tr>
                <tr><td>Best Practices</td><td>DevOps 工程最佳实践</td></tr>
                <tr><td>Tools</td><td>开发工具与 VS Code 扩展</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/diygod/blog", """
            <p>DIYGod 是国内知名开源开发者，RSSHub 和 RSS3 协议联合创始人，博客记录技术思考与项目进展。</p>
            <table class="about-table">
              <thead><tr><th>领域</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>Open Source</td><td>RSSHub、xLog 等开源项目动态</td></tr>
                <tr><td>Web3</td><td>RSS3 协议与去中心化应用</td></tr>
                <tr><td>Frontend</td><td>前端技术探索</td></tr>
                <tr><td>Life</td><td>生活随笔与思考</td></tr>
              </tbody>
            </table>"""),

        // ── programming ────────────────────────────────────────────────────────
        Map.entry("/github/releases/:owner/:repo", """
            <p>订阅任意 GitHub 公开仓库的 Release 版本更新，第一时间获取版本号、更新日志和发布说明。</p>
            <table class="about-table">
              <thead><tr><th>参数</th><th>说明</th></tr></thead>
              <tbody>
                <tr><td>:owner</td><td>仓库所有者（用户名或组织名）</td></tr>
                <tr><td>:repo</td><td>仓库名称</td></tr>
                <tr><td>示例</td><td>/github/releases/anthropics/claude-code</td></tr>
                <tr><td>过滤</td><td>跳过草稿（draft）版本</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/hn/:feed", """
            <p>Hacker News 是由 Y Combinator 运营的科技社区，是创业者、工程师和投资人的聚集地。</p>
            <table class="about-table">
              <thead><tr><th>:feed 值</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>top</td><td>当前综合热门</td></tr>
                <tr><td>new</td><td>最新提交</td></tr>
                <tr><td>best</td><td>历史最高质量</td></tr>
                <tr><td>ask</td><td>社区提问（Ask HN）</td></tr>
                <tr><td>show</td><td>项目展示（Show HN）</td></tr>
                <tr><td>job</td><td>职位招聘</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/cnblogs/post", """
            <p>博客园是国内历史悠久的技术博客平台，精华文章经编辑人工筛选，整体质量较高。</p>
            <table class="about-table">
              <thead><tr><th>领域</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>.NET / C#</td><td>ASP.NET、WPF、微软技术栈</td></tr>
                <tr><td>Java</td><td>Spring、JVM 生态技术</td></tr>
                <tr><td>前端</td><td>JavaScript、Vue、React</td></tr>
                <tr><td>运维</td><td>Linux、K8s、Docker</td></tr>
                <tr><td>数据库</td><td>MySQL、Redis、MongoDB</td></tr>
                <tr><td>算法</td><td>数据结构与竞赛</td></tr>
              </tbody>
            </table>"""),

        // ── academic ───────────────────────────────────────────────────────────
        Map.entry("/arxiv/:category", """
            <p>arXiv 是学术预印本平台，在论文正式发表前即可公开获取，覆盖计算机科学、数学、物理等领域。</p>
            <table class="about-table">
              <thead><tr><th>常用 :category</th><th>领域</th></tr></thead>
              <tbody>
                <tr><td>cs.AI</td><td>人工智能</td></tr>
                <tr><td>cs.LG</td><td>机器学习</td></tr>
                <tr><td>cs.CV</td><td>计算机视觉</td></tr>
                <tr><td>cs.CL</td><td>自然语言处理</td></tr>
                <tr><td>cs.CR</td><td>密码学与安全</td></tr>
                <tr><td>cs.SE</td><td>软件工程</td></tr>
                <tr><td>stat.ML</td><td>统计机器学习</td></tr>
                <tr><td>q-bio</td><td>定量生物学</td></tr>
              </tbody>
            </table>"""),

        // ── media ──────────────────────────────────────────────────────────────
        Map.entry("/bbc/news/:channel", """
            <p>BBC News 是英国广播公司旗下的国际新闻媒体，以客观报道著称，支持英文和中文频道，并抓取全文。</p>
            <table class="about-table">
              <thead><tr><th>:channel 值</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>world</td><td>国际新闻</td></tr>
                <tr><td>technology</td><td>科技</td></tr>
                <tr><td>world-asia</td><td>亚洲新闻</td></tr>
                <tr><td>science-environment</td><td>科学与环境</td></tr>
                <tr><td>business</td><td>商业财经</td></tr>
                <tr><td>chinese</td><td>BBC 中文（简体）</td></tr>
                <tr><td>traditionalchinese</td><td>BBC 中文（繁体）</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/bbc/learningenglish/:channel", """
            <p>BBC 英语学习频道提供专为学习者设计的音频、视频和文字内容，涵盖时事词汇与语法讲解，附全文。</p>
            <table class="about-table">
              <thead><tr><th>:channel 值</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>take-away-english</td><td>时事英语（每周更新）</td></tr>
                <tr><td>media-english</td><td>媒体英语（新闻词汇）</td></tr>
                <tr><td>lingohack</td><td>最新词汇与实际用法</td></tr>
                <tr><td>6-minute-english</td><td>六分钟英语</td></tr>
              </tbody>
            </table>"""),

        // ── Chinese media ──────────────────────────────────────────────────────
        Map.entry("/sspai/articles", """
            <p>少数派是国内专注 Apple 生态与效率工具的科技媒体，以高质量使用教程和应用评测著称。</p>
            <table class="about-table">
              <thead><tr><th>领域</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>iOS / macOS</td><td>Apple 系统使用技巧与深度评测</td></tr>
                <tr><td>效率工具</td><td>生产力应用评测与工作流分享</td></tr>
                <tr><td>硬件</td><td>数码产品测评与推荐</td></tr>
                <tr><td>自动化</td><td>快捷指令、脚本与自动化工作流</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/36kr/news", """
            <p>36氪是国内最具影响力的科技创业媒体，专注报道中国互联网、科技公司和创业生态。</p>
            <table class="about-table">
              <thead><tr><th>领域</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>创业融资</td><td>最新投融资动态与估值分析</td></tr>
                <tr><td>AI</td><td>国内 AI 产品、公司与政策</td></tr>
                <tr><td>新能源</td><td>电动车与能源科技</td></tr>
                <tr><td>消费科技</td><td>消费品牌与产品趋势</td></tr>
                <tr><td>互联网</td><td>大厂动态与行业分析</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/huanqiu/news", """
            <p>环球网是人民日报旗下的综合新闻网站，主要聚焦国际新闻与时事评论。</p>
            <table class="about-table">
              <thead><tr><th>领域</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>国际</td><td>全球政治、外交与冲突</td></tr>
                <tr><td>财经</td><td>国际经济动态与市场</td></tr>
                <tr><td>军事</td><td>国防与军事新闻</td></tr>
                <tr><td>社会</td><td>国内外社会事件</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/huanqiu/tech/:section", """
            <p>环球网科技频道聚焦国内外科技行业动态，支持多个子栏目。</p>
            <table class="about-table">
              <thead><tr><th>:section 值</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>original</td><td>人工智能（原创深度报道）</td></tr>
                <tr><td>it</td><td>IT 行业动态</td></tr>
                <tr><td>internet</td><td>互联网资讯</td></tr>
                <tr><td>automobile</td><td>智能汽车</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/xinhua/news", """
            <p>新华社是中国官方通讯社，提供权威的国内外新闻资讯，包括时政、财经、国际等内容。</p>
            <table class="about-table">
              <thead><tr><th>领域</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>时政</td><td>国内政策与党政动态</td></tr>
                <tr><td>国际</td><td>全球重要事件与外交</td></tr>
                <tr><td>财经</td><td>宏观经济与政策解读</td></tr>
                <tr><td>社会</td><td>民生与社会事件</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/cctv/news/:category", """
            <p>央视新闻是中央电视台的新闻平台，提供图文、视频、图集等多种内容形式，覆盖各类分类频道。</p>
            <table class="about-table">
              <thead><tr><th>:category 值</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>news</td><td>综合新闻</td></tr>
                <tr><td>china</td><td>国内</td></tr>
                <tr><td>world</td><td>国际</td></tr>
                <tr><td>society</td><td>社会</td></tr>
                <tr><td>law</td><td>法治</td></tr>
                <tr><td>ent</td><td>娱乐</td></tr>
                <tr><td>tech</td><td>科技</td></tr>
                <tr><td>life</td><td>生活</td></tr>
                <tr><td>edu</td><td>教育</td></tr>
              </tbody>
            </table>"""),

        // ── social ─────────────────────────────────────────────────────────────
        Map.entry("/twitter/user/:id", """
            <p>订阅 Twitter/X 上任意用户的公开推文，适合追踪科技博主、研究者或机构账号的最新发布。</p>
            <table class="about-table">
              <thead><tr><th>参数</th><th>说明</th></tr></thead>
              <tbody>
                <tr><td>:id</td><td>用户名（如 elonmusk）或 +数字ID（如 +44196397）</td></tr>
                <tr><td>前置条件</td><td>需在凭据页配置有效的 TWITTER_COOKIE</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/producthunt/daily", """
            <p>Product Hunt 是全球最知名的新产品发现平台，每日精选最值得关注的新应用、工具和服务。</p>
            <table class="about-table">
              <thead><tr><th>领域</th><th>内容</th></tr></thead>
              <tbody>
                <tr><td>AI Tools</td><td>生产力 AI 应用</td></tr>
                <tr><td>Developer Tools</td><td>开发者工具与 SDK</td></tr>
                <tr><td>Web Apps</td><td>SaaS 产品</td></tr>
                <tr><td>Mobile</td><td>iOS / Android 应用</td></tr>
                <tr><td>Hardware</td><td>硬件与设备</td></tr>
              </tbody>
            </table>"""),

        // ── video ──────────────────────────────────────────────────────────────
        Map.entry("/bilibili/user/video/:uid", """
            <p>订阅指定 B 站 UP 主的最新视频投稿，包含封面、标题和简介。</p>
            <table class="about-table">
              <thead><tr><th>参数</th><th>说明</th></tr></thead>
              <tbody>
                <tr><td>:uid</td><td>UP 主的用户 UID（主页 URL 中的数字）</td></tr>
                <tr><td>前置条件</td><td>需配置 BILIBILI_COOKIE</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/bilibili/user/dynamic/:uid", """
            <p>订阅指定 B 站 UP 主的全部动态，涵盖视频、图文、纯文字、专栏文章和转发。</p>
            <table class="about-table">
              <thead><tr><th>参数</th><th>说明</th></tr></thead>
              <tbody>
                <tr><td>:uid</td><td>UP 主的用户 UID</td></tr>
                <tr><td>前置条件</td><td>需配置 BILIBILI_COOKIE</td></tr>
                <tr><td>动态类型</td><td>视频、图文、纯文字、专栏、转发</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/bilibili/followings/dynamic/:uid", """
            <p>聚合已登录账号所有关注 UP 主的最新动态，相当于 B 站首页的关注动态流。</p>
            <table class="about-table">
              <thead><tr><th>参数</th><th>说明</th></tr></thead>
              <tbody>
                <tr><td>:uid</td><td>已登录用户自身的 UID</td></tr>
                <tr><td>前置条件</td><td>需配置完整的 BILIBILI_COOKIE（含 SESSDATA + bili_jct）</td></tr>
                <tr><td>动态类型</td><td>视频、图文、纯文字、专栏、转发</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/bilibili/followings/video/:uid", """
            <p>仅聚合已登录账号关注 UP 主的最新视频投稿，过滤掉图文和文字动态。</p>
            <table class="about-table">
              <thead><tr><th>参数</th><th>说明</th></tr></thead>
              <tbody>
                <tr><td>:uid</td><td>已登录用户自身的 UID</td></tr>
                <tr><td>前置条件</td><td>需配置 BILIBILI_COOKIE（SESSDATA 即可）</td></tr>
              </tbody>
            </table>"""),

        Map.entry("/bilibili/followings/article/:uid", """
            <p>仅聚合已登录账号关注 UP 主发布的专栏文章（图文长文），过滤掉视频和普通动态。</p>
            <table class="about-table">
              <thead><tr><th>参数</th><th>说明</th></tr></thead>
              <tbody>
                <tr><td>:uid</td><td>已登录用户自身的 UID</td></tr>
                <tr><td>前置条件</td><td>需配置 BILIBILI_COOKIE（SESSDATA 即可）</td></tr>
              </tbody>
            </table>"""),

        // ── generic ────────────────────────────────────────────────────────────
        Map.entry("/rss", """
            <p>代理任意外部 RSS 2.0 或 Atom 订阅源，统一格式后供客户端订阅，适合没有内置路由的内容源。</p>
            <table class="about-table">
              <thead><tr><th>使用方法</th><th>说明</th></tr></thead>
              <tbody>
                <tr><td>feedUrl</td><td>在路由配置中填写完整的 RSS / Atom 订阅地址</td></tr>
                <tr><td>格式支持</td><td>RSS 2.0 / Atom</td></tr>
                <tr><td>挂载路径</td><td>建议使用有意义的别名路径，如 /my-newsletter</td></tr>
              </tbody>
            </table>""")
    );

    private RouteAbout() {}
}
