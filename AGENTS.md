# AGENTS.md

fetch-news — self-hosted news aggregator, RSS proxy and reader.
Stack: **Spring Boot 4 · Java 25 · SQLite · Thymeleaf + HTMX + Alpine.js**; AI via Ollama, OpenAI- or Anthropic-compatible APIs.
Domain vocabulary and data flow: `CONTEXT.md`. Decisions: `docs/adr/`. Radar reports: `docs/radar.md`.

## Package map

```
app/                 entry point and wiring only: AppRuntime (registry, config, topics), AppConfig (beans),
                     BuiltInRoutes/CuratedRoutes (all routes), RssLiteResource (public feeds), FrontendAssetResource
app/core/            Feed/FeedItem model, Route, Topic, RouteRegistry, RouteConfig(+Store), CredentialConfig(+Store),
                     CacheService, FetchClient, RouteError/RouteException, RSS/JSON renderers, XmlEscaper
app/routes/          one RouteHandler per source; GenericRssRoute serves /rss mounts
app/fetch/           FeedFetcher (fetch → persist → log), FetchScheduler (60 s tick), FeedFetchCoordinator
app/store/           FeedStore (the only SQLite access) and the modules sharing its connection and lock:
                     AccountStore, ReadingState, SummaryQueue, PickStore, RadarStore, RadarJobs, … (feedStore.radar() …)
app/admin/           everything under /index: AdminResource, AdminItemsResource, AI/Feishu/subscription
                     settings pages, AdminTemplateRenderer; route blurbs come from resources/route-about.json
app/reader/          everything under /reader: ReaderResource, ReaderSummaryResource, radar pages,
                     ReaderTemplateRenderer, ReaderItems (builds every reader card) and view models
app/ai/              OllamaClient (multi-protocol facade), ArticleSummarizer, ArticleExtractor, ArticleTriage
app/auth/            AuthFilter, AuthService, login/account and user admin
app/radar/           built-in daily reports (精选日报): RadarConfig.BUILT_IN, BuiltInProfiles, scoring pipeline
app/feishu/          Feishu push
app/subscriptions/   subscription catalogue behind /index#subscriptions
app/support/         parsing, dates, URLs, cover images (builds on app.core; core never imports it)
templates/           index/ (admin, HTMX), reader/, auth/, shared/
```

## Core principles

1. One source = one class implementing `RouteHandler`, registered explicitly in `BuiltInRoutes` (curated catalog feeds in `CuratedRoutes`) — nowhere else. No annotations, classpath scanning, reflection-heavy libraries or dynamic class loading.
2. All outbound HTTP goes through the injected `FetchClient`; never build an `HttpClient` in a handler.
3. `FeedFetcher` owns fetch → persist → log; never duplicate it in controllers, handlers or the scheduler.
4. All database access goes through `app.store` — `FeedStore` and the modules it hands out. Tests may pass `null` for the store, so callers null-check.
   Inside a store module, writes and reads that must see uncommitted writes use `synchronized (store)` with `store.conn`;
   page queries outside transactions use `store.read(conn -> …)` and must only touch that `conn`.
5. Keep controllers thin and handlers stateless — handler instances are singletons.
6. Prefer source APIs and native RSS/Atom feeds; scrape HTML only when neither exists, after checking network traffic.

## Adding a route

1. Create `app/routes/MySourceRoute.java`; the constructor takes `FetchClient` (plus `CacheService` / `ObjectMapper` if needed).
2. In `handle(RouteContext ctx)`: build the URL from `ctx.pathParam(...)`, fetch with `client.get(url)`, parse once with JSoup or Jackson, map entries to `FeedItem`, return `new Feed(title, link, description, items)`.
3. Register in `BuiltInRoutes` (or `CuratedRoutes` for catalog feeds) with exactly one topic: `new Route("/my/:param", new MySourceRoute(defaultFetchClient), "Description", Topic.SOFTWARE)`. Topics are declared in `app.core.Topic`; regrouping old names goes into `Topic.RENAMED`.

To mount an external feed without code, create a `/rss` mount with a `feedUrl` in `/index` or `route-config.json`.

## Route rules

**Paths and parameters**
- Paths start with `/`; variable segments are `:param`. Configure routes through path params, never route-specific query params.
- `format` and `limit` are applied by `RssLiteResource` after the handler; do not implement them, pagination, "load more" or tag filtering in a handler. Fetch the first page only.

**Items**
- Use only `FeedItem` fields: `title`, `link`, `description`, `pubDate`, `author`, `categories`, `image`. Omit absent values rather than passing `null` explicitly.
- `link` is the real source URL and the RSS `<guid>`: unique per item, never the feed URL.
- `description` is the main body HTML only — no title, author, date or tags. Tags go in `categories`.
- `pubDate` comes from the source via `DateParser`; never fabricate it or fall back to `Instant.now()`.
- `image` is the source's cover (thumbnail, og or list image). Leave it `null` when unknown — `FeedFetcher` falls back to the first image in `description`.
- Do not trim or truncate titles; rendering does that.
- The feed-level `link` is a human-readable page, not an API endpoint.

**Fetching**
- Fetching N detail pages: wrap each in `cacheService.getDetailPage(url, () -> client.get(url))` and fetch concurrently (`CompletableFuture` or virtual threads) rather than in a sequential loop.
- Extract changing tokens or hashes from the upstream page instead of hardcoding them.

**Errors** — throw `new RouteException(RouteError.X, "actionable message")` and let it propagate (do not re-wrap):
`INVALID_PARAMETER` bad or missing input · `UPSTREAM_REQUEST_FAILED` network/HTTP failure · `UPSTREAM_INVALID_CONTENT` unparseable response · `EMPTY_FEED` zero items. Never return a fake message item to dodge the empty-feed check.

## Route configuration

Fields are listed in `CONTEXT.md`. Two rules matter for code:
- Copy a `RouteConfig` with its `with…` methods so fields added later are not dropped.
- Ask `AppRuntime.isTemplate` whether a route is a template (path pattern or bare `/rss`); never compare display labels such as a fetch-strategy name.
- Resolve a route's topic only through `AppRuntime.categoryOf` — it applies a route's own `category` before inheriting the source route's.
- Build reader cards only through `ReaderItems`, so summary, excerpt, cover and labels stay consistent across pages.

## Authentication

- `AuthFilter` protects every endpoint. `/index/**` is admin-only; add other admin-only writes to `AuthFilter.classify`.
- New static assets go into `FrontendAssetResource.ASSETS`, or the login page cannot load them.
- Per-user data (bookmarks, read state, pick feedback) is keyed by `CurrentUser.id()`, never stored globally.
- Never log or persist plain tokens; store `AuthTokens.hash(token)`. The one-time bootstrap log line in `AuthService` is the sole exception.

## AI layer

- AI is opt-in (`AiConfig.enabled()`). `OllamaClient` reads the active platform on every call; all generation shares one permit that admits interactive work before background work. Generations are background unless run inside `ollama.interactive()`.
- Summaries are on demand. A reader's click is interactive and runs before background work; background tasks (from `autoSummary` routes or admin batch) expire after 24 h or once the item is read. Never enqueue every fetched item.
- `ArticleTriage` labels at most three 必知 and three 精选 articles per category over 24 h by asking the model to choose within a batch, never by per-article scoring. Labels live in `item_picks`.
- Readers rate labels 准/不准 (`pick_feedback`). Recent verdicts become prompt examples, and a rejected label is never re-applied to that article.

## Testing

- JUnit 5 under `src/test/java`, mirroring the main packages; HTTP flows use Spring Boot + RestAssured (e.g. `RssLiteResourceTest`).
- Gradle runs each test task in `build/test-runtime/<task>/` with its own database and config files and auth disabled; tests never touch the developer checkout.
- Test new route paths, parameter handling and edge-case rendering. Send non-ASCII form values with `charset=UTF-8`.
- Tests in package `app` that render pages or need a runtime use `TestPages.engine()` and `TestPages.runtime(configs, routes…)`.
- Simulate storage failures at the database (rename a table, add a failing trigger) rather than subclassing stores.

## Style

- No source file over 500 lines (Java, templates, JS, CSS; vendored `*.min.js` excepted). `SourceSizeTest` enforces it — split by responsibility, not by line count.
- New HTTP endpoints go into the package of their audience: `app.admin` for `/index`, `app.reader` for `/reader`; domain logic stays in its feature package (`app.ai`, `app.radar`, …).
- Records for data types; small single-purpose classes; specific, sorted imports (no wildcards, no inline fully-qualified names); `camelCase`.
- Format like the surrounding code: one statement per line, spaces around operators, lines ≤ 120 columns. Write prompts and multi-line SQL as text blocks.
- Comments only for non-obvious constraints or workarounds; no multi-paragraph Javadoc on obvious methods. English for code and comments.
- Helpers live at class level, not inside `handle()` or loops. Use `try-with-resources` for `Closeable`s, `startsWith()` for prefix checks, and string literals instead of `String.format` without substitutions.
