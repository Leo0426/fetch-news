# fetch-news

[中文文档](README_CN.md)

> **Personal use only.** This is a personal information aggregation project, built and maintained for individual use.

A self-hosted news aggregator that works like RSSHub but keeps everything local — feeds, state, and AI — with no cloud subscriptions, no tracking, and no per-API-call costs.

**Spring Boot 4 · SQLite · Ollama · Java 25**

---

## Roadmap

- [ ] **Push notifications** — WeChat / Feishu webhook integration for digest delivery
- [ ] **Export to Markdown** — one-click export of articles or digests to `.md` files

---

## Why fetch-news

| | fetch-news | Typical cloud reader |
|---|---|---|
| Data ownership | Your machine, your SQLite file | Vendor's servers |
| AI summaries | Local Ollama, zero API cost | Paid cloud API |
| Offline reading | Cached fallback, always available | Depends on connectivity |
| Infrastructure | Single JAR + one file | Managed subscription |
| Customisation | Add a route = one Java class | Limited or none |

---

## Feature highlights

### Reader

- **Digest view** — all recent items grouped by source, switchable between 24 h / 48 h / 3 d / 7 d windows
- **Read / Unread** — per-article state stored in the browser; filter to show only unread
- **Bookmarks** — save articles server-side (SQLite); dedicated bookmarks page with one-click removal
- **Full-text search** — instant search across every cached title and AI summary
- **AI summaries** — generate 2–3 sentence Chinese summaries on demand or in bulk via local Ollama; cached forever so the model is only called once per article

### Route config (`/index`)

- **Route management** — enable/disable, set cache TTLs, schedule background fetches per route
- **Cron scheduling** — per-route cron expressions (`0 0 8 * * *`) or simple minute intervals
- **Feed health dashboard** — per-route success rate bar, average fetch time, last error, never-fetched indicator
- **Items browser** — paginated view of every stored article, inline AI summary generation
- **Mount aliases** — expose a fixed path for a parameterised route (e.g. `/my-hn` → `/hn/top`)
- **Generic RSS/Atom** — mount any external feed URL via `/rss` + `feedUrl` in config

### Infrastructure

- **Zero external services** — SQLite for everything: feed items, bookmarks, AI cache, fetch logs
- **Offline fallback** — when an upstream source fails, the last cached items are served silently
- **Docker-ready** — one command to start; all data lives in a host-mounted `./data/` directory
- **OLLAMA_HOST env var** — point to a remote Ollama instance without editing any config file

---

## Quick start

```shell
# Local development
./gradlew bootRun
```

```shell
# Docker (builds image and starts with external data mount)
docker compose up -d
```

The app listens on <http://localhost:8080>.  
Route config: <http://localhost:8080/index>  
Reader: <http://localhost:8080/reader>  
Bookmarks: <http://localhost:8080/reader/bookmarks>

---

## Docker deployment

All persistent data is stored in `./data/` on the host — the container itself is stateless.

```
./data/
  feed-store.db       ← articles, bookmarks, AI summaries, fetch logs
  route-config.json   ← route schedules and enable/disable state
  ai-config.json      ← Ollama host, model, enabled flag
```

To use a local Ollama instance from inside Docker, set `OLLAMA_HOST` in `docker-compose.yml`:

```yaml
environment:
  OLLAMA_HOST: "http://host.docker.internal:11434"
```

To rebuild after a code change:

```shell
docker compose build --no-cache && docker compose up -d
```

---

## Routes

### Tech blogs

| Path | Description |
|---|---|
| `/techcrunch` | TechCrunch latest articles |
| `/wired/latest` | WIRED latest stories |
| `/zed/blog` | Zed editor blog |
| `/mritd/blog` | mritd 技术博客 |
| `/microsoft/devops` | Microsoft DevOps Blog |
| `/diygod/blog` | DIYGod 个人博客 |

### Programming

| Path | Description |
|---|---|
| `/hn/:feed` | Hacker News — `:feed` = `top` / `new` / `best` / `ask` / `show` / `job` |
| `/github/releases/:owner/:repo` | GitHub releases for any public repository |
| `/github/issues/:owner/:repo` | Open GitHub issues for any public repository |
| `/github/stars/:user` | Recently starred repositories by a GitHub user |
| `/stackoverflow/tag/:tag` | Stack Overflow questions for a tag |
| `/cnblogs/post` | 博客园 (cnblogs) featured blog posts |

### Academic

| Path | Description |
|---|---|
| `/arxiv/:category` | arXiv preprints — `:category` e.g. `cs.AI`, `cs.LG`, `math.CO` |

### English media

| Path | Description |
|---|---|
| `/bbc/news/:category` | BBC News — `:category` e.g. `world`, `technology`, `business`, `health` |

### Chinese media

| Path | Description |
|---|---|
| `/sspai/articles` | 少数派最新文章 |
| `/36kr/news` | 36氪最新资讯 |
| `/huanqiu/news` | 环球网最新新闻 |
| `/xinhua/news` | 新华网最新新闻 |
| `/cctv/7` | CCTV-7 国防军事频道最新节目 |

### Community & product

| Path | Description |
|---|---|
| `/reddit/r/:subreddit` | Reddit hot posts for a subreddit |
| `/producthunt/daily` | Product Hunt daily featured products |

### Video

| Path | Description |
|---|---|
| `/bilibili/user/video/:uid` | Bilibili UP主最新视频 — `:uid` 为用户 UID |

### Generic

| Path | Description |
|---|---|
| `/rss` | Any RSS 2.0 or Atom feed — set `feedUrl` in route config to point at the target feed |

---

All routes return RSS 2.0 by default. Append `?format=json` for JSON and `?limit=N` to cap item count.

```shell
curl http://localhost:8080/hn/top
curl http://localhost:8080/arxiv/cs.AI
curl http://localhost:8080/github/releases/spring-projects/spring-boot
curl 'http://localhost:8080/hn/top?format=json&limit=5'
```

---

## Adding a route

A route is a single class implementing `RouteHandler`, registered in `AppRuntime`. The interface has one method:

```java
Feed fetch(RouteContext ctx) throws Exception;
```

No annotations, no framework magic. Register it in `AppRuntime` with a path, description, and category — it immediately appears in the route config page (`/index`) and is available for scheduling.

---

## Configuration

All data paths are overridable via system properties (defaults shown):

```shell
./gradlew bootRun \
  -Dfetch-news.db=./feed-store.db \
  -Dfetch-news.route-config=./route-config.json \
  -Dfetch-news.ai-config=./ai-config.json
```

The `OLLAMA_HOST` environment variable overrides the host stored in `ai-config.json` without modifying the file.

---

## Architecture

```
HTTP Request
  → Spring MVC Controller (RssLiteResource / ReaderResource)
    → RouteConfigStore       — enabled check, TTL resolution
    → RouteRegistry          — path-pattern matching
    → CacheService           — per-entry TTL cache (Caffeine)
    → FeedFetcher            — invoke handler, persist items, log fetch
      → RouteHandler         — fetch + parse upstream source
        → FetchClient        — Java HttpClient with retry
        → JSoup / Jackson    — HTML / XML / JSON parsing
      → FeedStore            — SQLite: items, bookmarks, AI cache, fetch log
    → FeedRenderer           — RSS 2.0 or JSON output
  → HTTP Response

Background
  → FetchScheduler           — ticks every 60 s, evaluates cron/interval per route
  → ArticleExtractor         — fetches full text, calls Ollama, caches in SQLite
  → ArticleSummarizer        — generates Chinese summary, caches in SQLite
```

**Key design decisions:**

- `RouteHandler` is a single-method interface — adding a source is one class, no annotations.
- Explicit route registration in `AppRuntime` — all routes visible in one place.
- `FeedRenderer` interface — adding Atom or other formats requires only a new implementation.
- `FeedFetcher` centralises fetch-persist-log shared by HTTP endpoint and scheduler.
- `CacheService` is route-config-aware — TTL set per route, not globally.

---

## Build

```shell
./gradlew build   # compile + test
./gradlew bootJar # fat JAR only → build/libs/fetch-news-*.jar
```

---

## Tech stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 4 / Spring MVC |
| Templates | Thymeleaf + HTMX + Alpine.js |
| HTTP client | Java built-in `HttpClient` |
| Parsing | JSoup (HTML/XML) · Jackson (JSON) |
| Cache | Caffeine — in-memory, per-route TTL |
| Persistence | SQLite via `sqlite-jdbc` |
| AI | Ollama (local) — extract + summarise |
| Build | Gradle |
| Java | 25 |
