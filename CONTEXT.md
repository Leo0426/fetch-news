# CONTEXT.md

## Product

fetch-news is a self-hosted news aggregator and RSS proxy.

It receives HTTP requests for route paths, fetches content from source websites or APIs, normalises it into a common feed model, and returns RSS 2.0 or JSON. It also stores fetched items in a local SQLite database, serves a reader UI, and optionally generates AI summaries via a local Ollama instance.

The project is intentionally smaller than RSSHub. Core mechanics:

- route matching
- source fetching
- parsing and normalisation
- in-memory caching
- RSS 2.0 and JSON rendering
- SQLite persistence (items, bookmarks, AI cache, fetch log)
- background scheduling per route
- local AI summarisation (Ollama)
- reader UI (Thymeleaf + HTMX + Alpine.js)
- route config UI at `/index`

---

## Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 4 / Spring MVC |
| Java | 25 |
| Templates | Thymeleaf + HTMX + Alpine.js |
| HTTP client | Java built-in `HttpClient` |
| Parsing | JSoup (HTML/XML) · Jackson (JSON) |
| Cache | Caffeine — in-memory, per-route TTL |
| Persistence | SQLite via `sqlite-jdbc` |
| AI | Ollama (local) — full-text extraction + Chinese summarisation |
| Build | Gradle |

---

## Pages

| URL | Purpose |
|---|---|
| `/index` | Route config: enable/disable, TTL, schedule, mount aliases, health, AI settings |
| `/reader` | Digest reader — recent items grouped by source |
| `/reader/bookmarks` | Saved bookmarks |
| `/reader/search` | Full-text search across cached titles and AI summaries |
| `/<route-path>` | RSS 2.0 or JSON feed output |

All pages are publicly accessible — there is no authentication layer.

---

## Domain Terms

**Feed** — normalised subscription feed returned by a route. Contains title, link, description, and a list of FeedItems.

**FeedItem** — one article, post, release, or notification. Fields: title, link, description, pubDate, author, categories.

**Route** — a public HTTP path mapped to one RouteHandler. Examples: `/hn/:feed`, `/github/releases/:owner/:repo`, `/rss`.

**RouteHandler** — single-method interface that produces a Feed. One class per source. No annotations or framework magic.

**RouteRegistry** — explicit registry mapping route path patterns to RouteHandlers. Registered manually in `AppRuntime`.

**RouteConfig** — persisted per-route configuration: enabled flag, cache TTLs, schedule (minutes or cron), sourcePath alias, feedUrl. Stored in `route-config.json`.

**Mount alias** — a fixed path that delegates to a parameterised route. Example: `/my-hn` → sourcePath `/hn/top`. Created via the `/index` UI.

**Generic RSS route** — the `/rss` route accepts any external RSS 2.0 or Atom feed URL via the `feedUrl` field in RouteConfig. Used to mount arbitrary external feeds without writing a new handler.

**FetchClient** — shared HTTP client abstraction. All outbound requests go through this layer (timeout, User-Agent, retries, error messages).

**CacheService** — Caffeine-based in-memory TTL cache. Two levels: route-level feed cache and detail-page cache.

**FeedFetcher** — centralises the fetch → persist → log cycle shared by the HTTP endpoint and the background scheduler.

**FetchScheduler** — background thread that fires every 60 seconds, evaluates per-route cron or minute-interval schedules, and calls FeedFetcher.

**FeedStore** — SQLite persistence layer. Tables: items, bookmarks, summaries (AI cache), fetch_log, route_health.

**OllamaClient** — thin wrapper around the Ollama local REST API. Host and model are read from `ai-config.json` on every call.

**ArticleExtractor** — fetches full article HTML and extracts main text for items that have no description.

**ArticleSummarizer** — calls Ollama to generate a 2-3 sentence Chinese summary per item. Results cached in SQLite.

---

## Data Flow

```
HTTP Request
  → RssLiteResource / ReaderResource
    → RouteConfigStore   — enabled check, TTL, feedUrl resolution
    → RouteRegistry      — path-pattern matching → RouteMatch
    → CacheService       — return cached Feed if fresh
    → FeedFetcher        — invoke handler, persist items, log fetch
      → RouteHandler     — fetch + parse upstream (via FetchClient + JSoup/Jackson)
      → FeedStore        — upsert items, write fetch_log entry
    → FeedRenderer       — RSS 2.0 (RssRenderer) or JSON (JsonRenderer)
  → HTTP Response

Background
  → FetchScheduler       — ticks every 60 s, triggers FeedFetcher per schedule
  → ArticleExtractor     — fetches full text for items without description
  → ArticleSummarizer    — generates Chinese summary, caches in SQLite
```

---

## Feed Model

```java
record Feed(String title, String link, String description, List<FeedItem> items) {}

record FeedItem(
    String title,
    String link,
    String description,
    Instant pubDate,
    String author,
    List<String> categories
) {}
```

---

## Common Request Parameters

| Parameter | Behaviour |
|---|---|
| `format=rss` | RSS 2.0 XML (default) |
| `format=json` | JSON — debugging and lightweight API use |
| `limit=N` | Cap item count |

---

## Route Config Fields (`route-config.json`)

| Field | Type | Meaning |
|---|---|---|
| `enabled` | boolean | Whether the route is active |
| `sourcePath` | string | Registered source route (for mount aliases) |
| `routeCacheTtlSeconds` | int | Feed-level cache TTL (default 300) |
| `detailCacheTtlSeconds` | int | Detail-page cache TTL (default 1800) |
| `scheduleMinutes` | int | Background fetch interval in minutes; 0 = disabled |
| `scheduleCron` | string | Spring 6-field cron expression (takes precedence over scheduleMinutes) |
| `feedUrl` | string | External feed URL; only used when sourcePath is `/rss` |

---

## Caching Strategy

- Route-level cache TTL: configurable per route, default 5 minutes.
- Detail-page cache TTL: configurable per route, default 30 minutes.
- Cache keys include: path, source path, format, limit.
- Error responses are not cached.

---

## Persistence (SQLite)

All data lives in a single SQLite file (default `feed-store.db`, overridable via `-Dfetch-news.db`).

Tables: `items`, `bookmarks`, `summaries`, `fetch_log`, `route_health`.

The container is stateless; the file is host-mounted at `./data/feed-store.db` in Docker.

---

## Route Implementation Policy

A route is a single class implementing `RouteHandler`. Shape:

1. Build upstream URL (using path params or feedUrl from context).
2. Fetch via `FetchClient`.
3. Parse with JSoup (HTML/XML) or Jackson (JSON).
4. Map source entries to `FeedItem`.
5. Return `Feed`.

If a route fetches detail pages per item, use `CacheService` for the detail-page cache level. Never hold shared mutable state in a handler.

---

## System Properties

| Property | Default | Purpose |
|---|---|---|
| `fetch-news.db` | `./feed-store.db` | SQLite database path |
| `fetch-news.route-config` | `./route-config.json` | Route configuration file |
| `fetch-news.ai-config` | `./ai-config.json` | Ollama host, model, enabled flag |

---

## Non-Goals (current version)

- Authentication or multi-user support
- Distributed cache or external queue
- Browser automation
- Dynamic plugin loading or classpath scanning
- Full RSSHub common-parameter compatibility (Radar rules, OpenCC, custom filters)
- Pagination in route handlers
