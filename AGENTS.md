# AGENTS.md

## Project

fetch-news — self-hosted news aggregator and RSS proxy.  
Stack: **Spring Boot 4 · Java 25 · SQLite · Ollama · Thymeleaf + HTMX + Alpine.js**

See `CONTEXT.md` for full domain vocabulary and data flow.

---

## Package Layout

```
src/main/java/app/
  FetchNewsApplication.java     — Spring Boot entry point
  AppRuntime.java               — owns RouteRegistry, RouteConfigStore, CacheService; all routes registered here
  AppConfig.java                — Spring beans (ObjectMapper, FeedStore, etc.)
  AppInfo.java                  — version / build info
  RssLiteResource.java          — public feed endpoint: /<route-path>?format=&limit=
  ReaderResource.java           — reader UI endpoints: /reader, /reader/bookmarks, /reader/search
  AdminResource.java            — route config UI endpoints at /index (HTMX fragments)
  AdminTemplateRenderer.java    — Thymeleaf rendering helpers for /index templates
  FeedFetcher.java              — fetch → persist → log; shared by endpoint and scheduler
  FetchScheduler.java           — background scheduler, fires every 60 s

app/core/
  Feed.java, FeedItem.java      — immutable records: the canonical feed model
  Route.java                    — path + RouteHandler + description + category
  RouteHandler.java             — single-method interface: Feed handle(RouteContext)
  RouteRegistry.java            — path-pattern matching (supports :param segments)
  RouteMatch.java               — matched route + extracted path params
  RouteContext.java             — request context: path, pathParams, queryParams, detailCacheTtl, feedUrl
  RouteConfig.java              — persisted config record: enabled, TTLs, schedule, sourcePath, feedUrl
  RouteConfigStore.java         — read/write route-config.json; merges with registry defaults
  RouteError.java               — error codes with HTTP status
  RouteException.java           — runtime exception carrying a RouteError
  CacheService.java             — Caffeine TTL cache, two levels: route-feed and detail-page
  FetchClient.java              — interface; DefaultFetchClient uses Java HttpClient with retry
  FeedRenderer.java             — interface for RSS/JSON output
  RssRenderer.java              — hand-written RSS 2.0 XML renderer
  JsonRenderer.java             — JSON feed renderer

app/routes/                     — one class per source
  GenericRssRoute.java          — any RSS 2.0 or Atom URL (feedUrl from RouteContext)
  HackerNewsRoute.java
  GithubReleaseRoute.java
  GithubIssuesRoute.java
  GithubStarsRoute.java
  ArxivRoute.java
  BBCNewsRoute.java
  RedditRoute.java
  BilibiliVideoRoute.java
  StackOverflowRoute.java
  CnblogsRoute.java
  TechCrunchRoute.java
  WiredRoute.java
  ZedBlogRoute.java
  MritdRoute.java
  MsDevOpsRoute.java
  DiygodRoute.java
  SspaiRoute.java
  ThirtySixKrRoute.java
  HuanqiuRoute.java
  XinhuaRoute.java
  Cctv7Route.java
  ProductHuntRoute.java
  RssProxyRoute.java

app/store/
  FeedStore.java                — SQLite: items, bookmarks, summaries, fetch_log, route_health
  StoredItem.java, BookmarkedItem.java, FetchLogEntry.java, RouteHealth.java

app/ai/
  OllamaClient.java             — Ollama REST API wrapper
  AiConfig.java, AiConfigStore.java — Ollama host/model config, persisted to ai-config.json
  ArticleExtractor.java         — fetches full article HTML, extracts main text
  ArticleSummarizer.java        — generates 2-3 sentence Chinese summary via Ollama

app/reader/
  ReaderItem.java, ReaderGroup.java — view models for the reader digest page

app/support/
  DateParser.java               — multi-format date parsing utility
  FeedParser.java               — RSS/Atom XML parsing utility
  Urls.java                     — URL resolution helpers
  XmlEscaper.java               — XML text escaping

src/main/resources/templates/
  index/                        — route config UI (HTMX-driven, mounted at /index)
  reader/                       — reader, bookmarks, search pages
```

---

## Core Principles

1. One route = one class implementing `RouteHandler`. No annotations, no framework magic.
2. All routes registered explicitly in `AppRuntime` — one place, fully visible.
3. All outbound HTTP goes through `FetchClient` — consistent timeout, User-Agent, retry.
4. Cache at two levels: route-feed (default 5 min) and detail-page (default 30 min).
5. Prefer source APIs and native RSS/Atom feeds over HTML scraping.
6. Use HTML scraping only when no stable API or feed exists.
7. Do not hold shared mutable state in route handlers.
8. `FeedFetcher` centralises fetch-persist-log — never duplicate this logic in controllers or the scheduler.
9. No reflection-heavy libraries; no runtime classpath scanning; no dynamic class loading.
10. Keep controllers thin — delegate all feed logic to core classes.

---

## Adding a Route

1. Create `src/main/java/app/routes/MySourceRoute.java` implementing `RouteHandler`.
2. Constructor accepts `FetchClient` (and `CacheService` / `ObjectMapper` if needed).
3. In `handle(RouteContext ctx)`:
   - Build upstream URL using `ctx.pathParam("name")` or `ctx.queryParam("name")`.
   - Call `client.get(url)` to fetch.
   - Parse with JSoup or Jackson.
   - Map entries to `FeedItem` — include pubDate, author, real source link.
   - Return `new Feed(title, link, description, items)`.
4. Register in `AppRuntime` inside the `RouteRegistry` constructor call:
   ```java
   new Route("/my/path", new MySourceRoute(defaultFetchClient), "Description", "Category")
   ```
5. That's it — the route immediately appears in `/index` and is available for scheduling.

### Using the generic RSS route

To mount an external RSS 2.0 or Atom feed without writing a new handler:

1. In `/index`, create a mount alias with sourcePath `/rss` and set `feedUrl` to the target feed address.
2. Or add directly to `route-config.json`:
   ```json
   "/my-blog": {
     "sourcePath": "/rss",
     "feedUrl": "https://example.com/feed.xml",
     "enabled": true
   }
   ```

---

## Route Rules

**Do:**
- Include real source links in every FeedItem.
- Include `pubDate` when the source provides one.
- Strip HTML from description; keep it focused on main content.
- Use `CacheService` when fetching multiple detail pages per route.

**Don't:**
- Put title, author, date, or tags inside description.
- Generate or fabricate dates.
- Produce duplicate item links.
- Add route-specific query parameters without an explicit request.
- Implement pagination in route handlers.

---

## Common Parameters (handled by RssLiteResource)

| Parameter | Behaviour |
|---|---|
| `format=rss` | RSS 2.0 XML output (default) |
| `format=json` | JSON output |
| `limit=N` | Cap returned item count |

Route handlers do not need to handle these — they are applied by the HTTP layer after the handler returns.

---

## RouteConfig Fields

Routes can be configured in `/index` or directly in `route-config.json`:

| Field | Meaning |
|---|---|
| `enabled` | Whether the route is served |
| `sourcePath` | Source route path (for mount aliases) |
| `routeCacheTtlSeconds` | Feed cache TTL (default 300) |
| `detailCacheTtlSeconds` | Detail-page cache TTL (default 1800) |
| `scheduleMinutes` | Background fetch interval in minutes (0 = off) |
| `scheduleCron` | Spring 6-field cron; takes precedence over scheduleMinutes |
| `feedUrl` | External feed URL — only used when sourcePath is `/rss` |

---

## Error Handling

Throw `RouteException(RouteError.X, "message")` from handlers. The HTTP layer maps `RouteError` to the appropriate HTTP status. Standard errors:

- `ROUTE_NOT_FOUND` — unknown path
- `INVALID_PARAMETER` — bad or missing parameter (including blank feedUrl)
- `UPSTREAM_ERROR` — network or HTTP failure
- `PARSE_ERROR` — upstream returned unparseable content
- `EMPTY_FEED` — handler produced zero items

---

## Persistence Rules

- All database access goes through `FeedStore`.
- `FeedStore` is injected as `null` in unit tests — all callers must null-check before using it.
- `FeedFetcher` persists items and writes fetch log entries — do not duplicate this in route handlers or controllers.

---

## AI Layer

- `OllamaClient` reads host and model from `AiConfigStore` on every call — config changes take effect without restart.
- `ArticleSummarizer` generates summaries and caches them in SQLite — each item is summarised at most once.
- `ArticleExtractor` fetches full article HTML when an item has no description; result also cached.
- AI features are opt-in: disabled when `AiConfig.enabled()` is false.

---

## Testing

Test file: `src/test/java/app/RssLiteResourceTest.java`

- Uses Spring Boot integration test with RestAssured.
- Route config is written to `build/test-route-config.json` (set via system property).
- Database is `build/test-feed-store.db`.
- Add tests for: new route registry paths, edge-case RSS rendering, parameter handling.

---

## Style

- Records for all data types where practical.
- Small classes with clear, single responsibilities.
- No comments explaining what the code does — only comments explaining non-obvious constraints or workarounds.
- No multi-paragraph Javadoc on obvious methods.
- English for all code and comments.

---

## Route Review Checklist

### Route Registration

- Route path must start with `/` and use `:param` for variable segments (e.g., `/github/release/:owner/:repo`).
- Register the route in `AppRuntime` — nowhere else.
- Provide exactly ONE category string per `Route(...)` constructor call.
- Do not create separate config or doc files for a route. Put the description in the `Route` constructor.
- Do not add routes to any deprecated or legacy router file.

### Code Style

- Use `camelCase` for all variable and method names.
- Use specific imports, not wildcard imports (`import app.core.*` is banned).
- Keep imports sorted (IDE autoformat on save).
- Avoid `String.format(...)` for plain strings with no substitution — use a string literal.
- Parse a JSoup `Document` once, then reuse `$` (the `Document`). Do not call `Jsoup.parse()` again on the same content.
- Define helper methods at class level, not inside `handle()` or inside loops.
- Do not explicitly pass `null` for a `FeedItem` field that simply doesn't exist — `null` is the natural absent value; omit the assignment.
- Use only fields defined on `FeedItem`: `title`, `link`, `description`, `pubDate`, `author`, `categories`. Do not add phantom fields.
- Prefer `startsWith()` over `contains()` when checking a string prefix.
- Combine optional-value chains with `||`-equivalent patterns (e.g., `Optional.ofNullable(a).orElse(b)` or ternary) rather than multi-step `if` blocks.
- Use `try-with-resources` for anything that implements `Closeable`.

### Data Handling

- **Cache detail pages**: when a route fetches N article detail pages inside a loop, wrap each fetch in `cacheService.getDetailPage(url, () -> client.get(url))`.
- **description** = main article body HTML only. Do NOT put `title`, `author`, `pubDate`, or tag text inside `description`.
- **categories** = tags/labels extracted from the source. Do not embed them in `description`.
- **pubDate**: always populate when the source provides a date. Use `DateParser` utilities.
- Do NOT use `Instant.now()` as a fallback for `pubDate`. Leave it `null` if the source has no date.
- Do not manually trim or truncate titles. The rendering layer handles that.
- Every item's `link` must be unique — it is used as the RSS `<guid>`. Do not fall back to the feed URL.
- The feed-level `link` in `new Feed(title, link, ...)` must point to a human-readable webpage, not an API endpoint.

### API and Data Fetching

- Prefer stable source APIs and native RSS/Atom feeds over HTML scraping. Check network traffic before writing a scraper.
- Jackson's `ObjectMapper.readTree()` already parses JSON — do not call `JSON.parse` or double-decode.
- Only fetch the first page of results. Do not add pagination or "load more" logic.
- Do not add a `limit` query parameter to routes — `limit=N` is already handled by `RssLiteResource`.
- Use path parameters (`:param`) for route configuration. Do not read route-specific options from query parameters.
- Do not implement tag/category filtering inside a route handler. Users apply filters via common parameters.
- If an API requires a token or hash that changes over time, extract it dynamically from the upstream page rather than hardcoding it.
- All outbound HTTP must go through the injected `FetchClient`. Never construct a raw `HttpClient` inside a route handler.

### Error Handling

- Throw `new RouteException(RouteError.X, "clear message")` with an actionable message that explains what went wrong.
- Use `INVALID_PARAMETER` for missing/malformed path params; `UPSTREAM_REQUEST_FAILED` for HTTP/network errors; `UPSTREAM_INVALID_CONTENT` for unparseable responses; `EMPTY_FEED` when the handler produces zero items.
- Do not catch `RouteException` and re-wrap it — let it propagate.
- Do not return an empty item list with a fake message item to avoid the empty-feed check.

### Concurrency

- Avoid sequential `client.get()` calls inside a loop when items can be fetched concurrently. Prefer `CompletableFuture` + `CacheService.getDetailPage()` or a structured virtual-thread approach.
- Do not share mutable state between route handler invocations. `RouteHandler` instances are singletons.
