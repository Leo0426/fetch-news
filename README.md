# fetch-news

自托管的个人信息过滤器：订阅精选来源，统一输出标准 RSS，再用内置阅读器和 AI 帮你挑出值得读的内容。

**订阅**

- 内置 74 个可直接订阅的来源，分属 13 个主题，从时事、AI、软件开发到设计、游戏。选择更新频率后点「订阅并抓取」即可，不需要填地址或密钥。
- 也可以添加任意 RSS / Atom 地址，或通过参数化路由订阅 GitHub 仓库发布、arXiv 分类、B 站 UP 主、Telegram 公开频道等。
- 每个来源都输出标准 RSS 2.0（`?format=json` 输出 JSON），可以接入任何 RSS 阅读器。

**阅读**

- **今日速览**：按来源分组的卡片，带封面、摘录和已读/收藏状态。
- **每日简报**：按主题汇总当天内容，相同报道自动折叠，可翻看历史日期。
- **站内阅读**：阅读已保存的正文，可调字号、收藏，返回时恢复原来的列表位置。
- **收藏**和**全文搜索**（标题、正文与摘要）。
- 键盘操作：`j` / `k` 上下移动，`o` 打开，`b` 收藏，`r` 标为已读。

**AI**（可选，默认关闭）

- **摘要**：在文章上点「AI 摘要」即可生成，你点的任务优先于后台任务处理。也可以为个别来源开启自动摘要，每次抓取最多为 5 篇新文章生成。
- **必知 / 精选**：AI 每个主题每天最多挑 3 篇「必知」（重大、影响面广）和 3 篇「精选」（有深度、值得细读），并附上理由。点「准 / 不准」反馈后，之后的挑选会参考你的判断。
- 支持 Ollama、OpenAI 兼容接口和 Anthropic 接口，可以保存多个平台并随时切换。详见 [AI 平台配置](docs/ai-platforms.md)。

**推送与日报**

- **飞书推送**：新文章按来源即时推送，或按主题定时推送摘要。
- **精选日报**：启用 AI 后，每天 8 点自动为前一天的文章打分、合并同一事件的报道，生成一份带中文解读的日报，也可以在 `/reader/radar` 随时手动生成。详见 [精选日报](docs/radar.md)。

**多账户**：令牌登录，每个账户的收藏、已读和反馈互相独立，适合通过内网穿透分享给家人朋友。

## 信息源

完整目录、介绍和推荐更新频率都在后台「订阅来源」页。下面按主题列出代表来源：


| 主题   | 代表来源                                                                |
| ---- | ------------------------------------------------------------------- |
| 时事要闻 | BBC News、The Guardian、联合国新闻中文、香港电台、中新网、新华网、央视新闻                     |
| AI   | OpenAI、Google Research、Hugging Face、Ollama 与 Transformers 版本发布      |
| 科技资讯 | 阮一峰、Solidot、少数派、36氪、Hacker News、TechCrunch、WIRED、Hackaday           |
| 软件开发 | GitHub Changelog、Cloudflare、Martin Fowler、Netflix TechBlog、InfoQ 中文 |
| 网络安全 | Google Security、Krebs on Security、Schneier on Security              |
| 财经商业 | 欧洲央行博客、美联储 FEDS Notes、中新网财经                                         |
| 科学   | NASA、Quanta Magazine、Science News、Carbon Brief、NOAA、arXiv           |
| 健康医学 | KFF Health News、STAT                                                |
| 人文社会 | Aeon、Psyche、JSTOR Daily、Our World in Data、Pew Research              |
| 设计艺术 | Smashing Magazine、Colossal、Aperture、Dezeen、ArchDaily                |
| 游戏   | Godot、Factorio Friday Facts、机核、游研社                                  |
| 关注动态 | B 站 UP 主与关注动态、X 用户时间线、Telegram 公开频道                                 |


可以在路由设置里为来源换一个主题。没有现成路由的网站，用通用路由 `/rss` 加上 `feedUrl` 即可挂载任意 RSS / Atom 源。

**需要凭据的来源**：在后台「凭据配置」页保存，或在 `docker-compose.yml` 中设置环境变量。


| 来源                 | 需要的凭据                                         |
| ------------------ | --------------------------------------------- |
| B 站公开路由（UP 主视频、动态） | `BILIBILI_COOKIE`，只需 `buvid3` 和 `buvid4`，不用登录 |
| B 站关注动态            | `BILIBILI_COOKIE_<你的 UID>`，已登录账号的 Cookie      |
| X（Twitter）         | `TWITTER_COOKIE`，需包含 `auth_token` 和 `ct0`     |


X 和 B 站的接口可能因登录过期、限流或风控而失效。Telegram 频道读取公开网页预览，不需要登录或 Bot Token。

- [AGENTS.md](AGENTS.md)：包结构、新增路由的步骤和代码约定。
- [CONTEXT.md](CONTEXT.md)：领域术语和数据流。
- [docs/adr/](docs/adr/)：架构决策记录。