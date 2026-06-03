# fetch-news

[English](README.md)

> **自用项目。** 这是一个个人信息获取项目，仅供个人使用。

一个自托管的新闻聚合器，工作方式类似 RSSHub，但将一切保持在本地——订阅源、状态和 AI——无需云订阅、无追踪、无按次计费。

**Spring Boot 4 · SQLite · Ollama · Java 25**

---

## 后续计划

- [ ] **消息推送** — 微信 / 飞书 Webhook 集成，支持摘要推送
- [ ] **导出 Markdown** — 一键将文章或摘要导出为 `.md` 文件

---

## 为什么选择 fetch-news

| | fetch-news | 典型云端阅读器 |
|---|---|---|
| 数据所有权 | 你的机器，你的 SQLite 文件 | 厂商服务器 |
| AI 摘要 | 本地 Ollama，零 API 成本 | 付费云 API |
| 离线阅读 | 缓存兜底，始终可用 | 依赖网络连通性 |
| 基础设施 | 单个 JAR + 一个文件 | 托管订阅服务 |
| 定制扩展 | 新增一个来源 = 一个 Java 类 | 受限或不支持 |

---

## 功能亮点

### 阅读器

- **速览视图** — 按来源分组展示最近内容，可在 24 小时 / 48 小时 / 3 天 / 7 天窗口间切换
- **已读 / 未读** — 每篇文章的阅读状态存储在浏览器本地；支持筛选只看未读
- **书签收藏** — 服务端持久化（SQLite），专属收藏夹页面，一键取消收藏
- **全文搜索** — 即时搜索所有已缓存的标题和 AI 摘要
- **AI 摘要** — 通过本地 Ollama 按需或批量生成 2-3 句中文摘要；结果永久缓存，每篇文章只调用一次模型

### 路由配置（`/index`）

- **路由管理** — 启用/禁用路由，调整缓存 TTL，为每条路由配置后台抓取计划
- **Cron 调度** — 每条路由支持 cron 表达式（`0 0 8 * * *`）或简单分钟间隔
- **Feed 健康仪表板** — 每条路由的成功率进度条、平均耗时、最近错误、从未抓取提示
- **条目浏览器** — 分页查看所有已存储文章，支持内联生成 AI 摘要
- **挂载别名** — 为参数化路由创建固定路径别名（如 `/my-hn` → `/hn/top`）
- **通用 RSS/Atom** — 通过 `/rss` + 路由配置中的 `feedUrl` 挂载任意外部订阅源

### 基础设施

- **零外部服务依赖** — SQLite 承载一切：文章条目、书签、AI 缓存、抓取日志
- **离线兜底** — 上游来源故障时，自动静默返回最近一次缓存内容
- **开箱即用的 Docker 支持** — 一条命令启动；所有数据存储在宿主机挂载的 `./data/` 目录
- **OLLAMA_HOST 环境变量** — 无需修改任何配置文件即可指向远程 Ollama 实例

---

## 快速开始

```shell
# 本地开发
./gradlew bootRun
```

```shell
# Docker（构建镜像并以外挂数据目录方式启动）
docker compose up -d
```

应用监听 <http://localhost:8080>  
路由配置：<http://localhost:8080/index>  
阅读器：<http://localhost:8080/reader>  
收藏夹：<http://localhost:8080/reader/bookmarks>

---

## Docker 部署

所有持久化数据存储在宿主机的 `./data/` 目录，容器本身是无状态的。

```
./data/
  feed-store.db       ← 文章、书签、AI 摘要、抓取日志
  route-config.json   ← 路由调度和启用/禁用状态
  ai-config.json      ← Ollama 地址、模型、启用开关
```

在 Docker 内使用本地 Ollama，在 `docker-compose.yml` 中设置 `OLLAMA_HOST`：

```yaml
environment:
  OLLAMA_HOST: "http://host.docker.internal:11434"
```

代码变更后重新构建：

```shell
docker compose build --no-cache && docker compose up -d
```

---

## 路由列表

### 技术博客

| 路径 | 说明 |
|---|---|
| `/techcrunch` | TechCrunch 最新文章 |
| `/wired/latest` | WIRED 最新报道 |
| `/zed/blog` | Zed 编辑器博客 |
| `/mritd/blog` | mritd 技术博客 |
| `/microsoft/devops` | Microsoft DevOps Blog |
| `/diygod/blog` | DIYGod 个人博客 |

### 编程

| 路径 | 说明 |
|---|---|
| `/hn/:feed` | Hacker News — `:feed` 可选 `top` / `new` / `best` / `ask` / `show` / `job` |
| `/github/releases/:owner/:repo` | 任意公开仓库的 GitHub Releases |
| `/github/issues/:owner/:repo` | 任意公开仓库的 GitHub Issues |
| `/github/stars/:user` | 指定用户最近 star 的仓库 |
| `/stackoverflow/tag/:tag` | Stack Overflow 指定标签下的问题 |
| `/cnblogs/post` | 博客园精华博文 |

### 学术

| 路径 | 说明 |
|---|---|
| `/arxiv/:category` | arXiv 预印本 — `:category` 如 `cs.AI`、`cs.LG`、`math.CO` |

### 英文媒体

| 路径 | 说明 |
|---|---|
| `/bbc/news/:category` | BBC News — `:category` 如 `world`、`technology`、`business`、`health` |

### 中文媒体

| 路径 | 说明 |
|---|---|
| `/sspai/articles` | 少数派最新文章 |
| `/36kr/news` | 36氪最新资讯 |
| `/huanqiu/news` | 环球网最新新闻 |
| `/xinhua/news` | 新华网最新新闻 |
| `/cctv/7` | CCTV-7 国防军事频道最新节目 |

### 社区与产品

| 路径 | 说明 |
|---|---|
| `/reddit/r/:subreddit` | Reddit 指定板块热门帖子 |
| `/producthunt/daily` | Product Hunt 每日精选产品 |

### 视频

| 路径 | 说明 |
|---|---|
| `/bilibili/user/video/:uid` | Bilibili UP主最新视频 — `:uid` 为用户 UID |

### 通用

| 路径 | 说明 |
|---|---|
| `/rss` | 任意 RSS 2.0 或 Atom 订阅源 — 在路由配置中设置 `feedUrl` 指向目标地址 |

---

所有路由默认返回 RSS 2.0 格式。附加 `?format=json` 获取 JSON 输出，附加 `?limit=N` 限制条目数量。

```shell
curl http://localhost:8080/hn/top
curl http://localhost:8080/arxiv/cs.AI
curl http://localhost:8080/github/releases/spring-projects/spring-boot
curl 'http://localhost:8080/hn/top?format=json&limit=5'
```

---

## 添加新路由

一条路由就是一个实现 `RouteHandler` 接口的类，在 `AppRuntime` 中注册。接口只有一个方法：

```java
Feed fetch(RouteContext ctx) throws Exception;
```

无注解，无框架魔法。在 `AppRuntime` 中用路径、描述和分类注册后，该路由立即出现在路由配置页（`/index`）并可配置调度。

---

## 配置

所有数据路径均可通过系统属性覆盖（以下为默认值）：

```shell
./gradlew bootRun \
  -Dfetch-news.db=./feed-store.db \
  -Dfetch-news.route-config=./route-config.json \
  -Dfetch-news.ai-config=./ai-config.json
```

`OLLAMA_HOST` 环境变量会覆盖 `ai-config.json` 中保存的地址，无需修改文件。

---

## 架构

```
HTTP 请求
  → Spring MVC 控制器（RssLiteResource / ReaderResource）
    → RouteConfigStore       — 启用检查、TTL 解析
    → RouteRegistry          — 路径模式匹配
    → CacheService           — 按条目 TTL 的内存缓存（Caffeine）
    → FeedFetcher            — 调用处理器、持久化条目、记录抓取日志
      → RouteHandler         — 抓取并解析上游来源
        → FetchClient        — Java HttpClient（含重试）
        → JSoup / Jackson    — HTML / XML / JSON 解析
      → FeedStore            — SQLite：条目、书签、AI 缓存、抓取日志
    → FeedRenderer           — RSS 2.0 或 JSON 输出
  → HTTP 响应

后台
  → FetchScheduler           — 每 60 秒触发，按每路由 cron/间隔评估
  → ArticleExtractor         — 抓取全文、调用 Ollama、缓存至 SQLite
  → ArticleSummarizer        — 生成中文摘要、缓存至 SQLite
```

**核心设计决策：**

- `RouteHandler` 是单方法接口——新增来源只需一个类，无需注解。
- `AppRuntime` 中显式注册路由——所有路由一处可见。
- `FeedRenderer` 接口——新增输出格式（如 Atom）只需新建实现类。
- `FeedFetcher` 集中处理 HTTP 端点和调度器共用的抓取-持久化-日志循环。
- `CacheService` 感知路由配置——TTL 按路由设置，而非全局统一。

---

## 构建

```shell
./gradlew build   # 编译 + 测试
./gradlew bootJar # 仅生成 fat JAR → build/libs/fetch-news-*.jar
```

---

## 技术栈

| 层次 | 技术 |
|---|---|
| 框架 | Spring Boot 4 / Spring MVC |
| 模板 | Thymeleaf + HTMX + Alpine.js |
| HTTP 客户端 | Java 内置 `HttpClient` |
| 解析 | JSoup（HTML/XML）· Jackson（JSON）|
| 缓存 | Caffeine — 内存缓存，按路由 TTL |
| 持久化 | SQLite via `sqlite-jdbc` |
| AI | Ollama（本地）— 提取正文 + 生成摘要 |
| 构建 | Gradle |
| Java | 25 |
