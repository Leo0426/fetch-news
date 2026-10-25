# CONTEXT.md

## Product

fetch-news is a self-hosted news aggregator and RSS proxy.

It receives HTTP requests for route paths, fetches content from source websites or APIs, normalises it into a common feed model, and returns RSS 2.0 or JSON. It also stores fetched items in a local SQLite database, serves a reader UI, and optionally generates AI summaries through Ollama or compatible OpenAI Chat Completions / Anthropic Messages platforms.

The project is intentionally smaller than RSSHub. Core mechanics:

- route matching
- source fetching
- parsing and normalisation
- in-memory caching and persistent conditional RSS refresh
- RSS 2.0 and JSON rendering
- SQLite persistence (items, bookmarks, AI cache, fetch log)
- background scheduling per route
- local AI summarisation (Ollama)
- reader UI (Thymeleaf + HTMX + Alpine.js)
- read state, bookmarks, FTS5 search, and daily briefings
- optional Feishu immediate and scheduled digest delivery
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
| AI | Ollama / OpenAI Chat Completions / Anthropic Messages — full-text extraction + Chinese summarisation |
| Build | Gradle |

---

## Pages

| URL | Purpose |
|---|---|
| `/index` | Route config: enable/disable, TTL, schedule, mount aliases, health, AI settings |
| `/index#subscriptions` | Source discovery, subscribe with an initial fetch, progress, pause/resume, and source-filtered unread reading |
| `/reader` | Digest reader — recent items grouped by source |
| `/reader/unread` | Persistent unread inbox — all retained unread items, filtered and paged |
| `/reader/article?link=…` | Saved-content reading page — source text, labeled cached extracts, and explicit read/bookmark actions |
| `/reader/bookmarks` | Saved bookmarks |
| `/reader/search` | History search across saved titles, descriptions, and AI summaries |
| `/reader/briefing` | Daily briefing grouped by category |
| `/login` | Token sign-in; sets the HttpOnly `fn_auth` cookie |
| `/account` | Own feed URLs with feed key, OPML export, feed-key/token rotation, sign-out |
| `/index#users` | Admin: issue, rotate, disable, and delete access tokens |
| `/<route-path>` | RSS 2.0 or JSON feed output |

### Access control

`AuthFilter` gates every request except `/login`, `/logout`, the favicon, and assets listed in `FrontendAssetResource.ASSETS`. It is on by default; `fetch-news.auth.enabled=false` (`FETCH_NEWS_AUTH_ENABLED=false`) attaches the owner account to every request instead.

- **Account** (`users` table) — name, role, SHA-256 hash of the login token, plaintext feed key, last-seen time, disabled flag. Account id 1 is the owner; single-user bookmarks and read state migrate to it.
- **Login token** (`fn_…`, 256-bit) — shown once at creation/rotation. Accepted from the `fn_auth` cookie (browser), `Authorization: Bearer`, or as the HTTP Basic password.
- **Feed key** (`fk_…`) — read-only secret for RSS readers, accepted only on feed GETs via `?key=` or the Basic password. It never opens reader or admin pages, so a leaked feed URL exposes only feed output.
- **Roles** — `ADMIN` reaches everything; `MEMBER` reaches `/reader/**`, `/account/**`, and feeds. `/index/**` and writes to `/reader/radar/runs/**` are admin-only. Members opening `/` or `/index` are redirected to `/reader`.
- **Bootstrap** — without an owner token, startup generates one and logs it once. `FETCH_NEWS_ADMIN_TOKEN` (property `fetch-news.admin-token`, ≥16 cookie-safe characters) replaces the owner token on every start and is the recovery path.
- **Hardening** — cookie-authenticated writes must come from the same host (`Origin`/`Referer` vs `Host`/`X-Forwarded-Host`); the cookie is `SameSite=Lax`, `HttpOnly`, and `Secure` behind HTTPS (`X-Forwarded-Proto`). Paths with matrix parameters, dot segments, or encoded separators are rejected so filter and handler mapping cannot disagree. Redirects are relative so they stay correct behind TLS-terminating tunnels. Feed responses are `Cache-Control: private`.
- **Per-user state** — controllers read the caller with `CurrentUser.id()`; outside an HTTP request it resolves to the owner. `ReadingState` and the unread/article queries always take a user id; there is no implicit owner, so a caller cannot silently write someone else's state.

### UI behavior

- `admin.css` provides the shared warm-paper palette, responsive layouts, visible keyboard focus, and reduced-motion support.
- `ui.js` owns safe preference storage, transient status messages, progressive reveal, and reading progress. `reader.js` owns reader filters, actions, source navigation, and keyboard shortcuts. `admin.js` owns the HTMX navigation shell, mobile drawer, and modal focus management.
- Assets are explicitly served by `FrontendAssetResource`; new scripts must be registered there because feed paths use a catch-all controller.
- Recent-reader filters combine time-range content with category, title, and read/bookmark state. The read filter defaults to unread; read filter and layout preferences persist locally. In that view, “Mark all read” applies to every loaded item in the selected time range, including items hidden by client-side filters.
- The separate unread inbox filters the complete retained unread set by source, bookmark, and literal keyword before loading 50 items. Keywords match titles, descriptions, and saved summaries, including Chinese substrings. Next-page links preserve all filters. Counts cover matching unread articles across all pages; bulk read actions affect only the current page. The inbox shares layout preferences without changing the digest's saved read filter.
- Time, category, and bulk actions live in a native disclosure showing the selected range and category when closed. Search and read-state filters remain visible; mobile article actions use 44-pixel targets with explicit read-state labels.
- `reader/fragments.html` shares article cards between the digest and inbox, and summary status across the reader, search, and briefing. Reading pages show a compact AI summary, running, or failure label; timing diagnostics remain in the admin views.
- Summary submission returns queued/running JSON immediately. Reader cards, article detail pages, briefing primary and related stories, and admin item rows poll read-only status every two seconds, stop at terminal states, and restore pending work after reload. Connection failures stop polling and offer an explicit status check; briefing failures also link to the article page for manual recovery. Polling never submits generation work. Cached summaries remain visible with AI disabled. Detail-page generation and retries are explicit and do not block read/bookmark actions or navigation.
- Reader read and bookmark requests submit explicit desired states, coalesce pending clicks, and retain the last confirmed state on failure. Retrying after a lost response preserves the intended selection. Saves time out after 15 seconds and release controls for explicit retry. Bookmark deletion removes a card only after a successful response. Admin navigation ignores stale responses and commits its title and selection only after a successful content swap.
- Bookmark cards load persisted read state in one batch. Opening an external title, including a modified or middle click, marks that URL read idempotently without delaying navigation. Only a successful save updates the indicator; failures retain its prior state and offer a retry. Opening the separate detail link remains read-only.
- Reader shortcuts: `j`/`k` move through visible articles; `o` opens and marks an article read; `b` toggles bookmark; `r` toggles read state; `?` opens the shortcut hint. Form fields and modified key presses keep their native behavior.
- In the inbox, `r` and the read button mark articles read idempotently, including from stale tabs. Removing a card advances keyboard focus to the next article or the page-completion link. Read/bookmark saves block same-tab navigation and filter submission until the request finishes; failures restore cards and counts.
- The curated catalog includes 51 fixed sources. Its additional health, psychology, education, humanities, visual arts, architecture, data, maker, game-development and game-culture groups use verified publisher RSS/Atom endpoints and explicit recommendations. New sources remain disabled and unscheduled until subscribed. Reader and bookmark source names share one `SourceLabels` mapping; configured aliases still take precedence.
- All five reading lists expose a secondary “站内阅读” link; their existing external titles and `o` shortcut retain their behavior. Client-enhanced detail links preserve the current list path, query, and fragment as `returnTo`; the server accepts only exact reader list paths. Per-tab session storage remembers the filters and article position for a one-time return, while read/bookmark state comes from the refreshed server view. If the completed article disappears, restoration selects the next remaining visible article. Missing or unavailable storage falls back to normal navigation.
- Article detail GETs are read-only and load one `StoredArticle` snapshot from SQLite. Original descriptions of at least 200 Unicode code points take precedence; a shorter teaser uses a cached extract of at least 200 code points when available. Cached extracted content is labeled, and a missing body is distinct from a load error. Cached summaries stay visually separate. No article or model requests run while opening this page.
- `ArticleContent` is the only HTML rendering boundary for saved bodies: JSoup keeps readable structure and HTTP(S) links, strips active content and external media, and renders cached extracts as literal text. Reading saved content causes no automatic third-party resource request.
- Detail pages remember three font sizes locally. Explicit completion marks one URL read idempotently and returns only after persistence succeeds. Detail bookmark PUT requests set a desired state using canonical article metadata and preserve the original bookmark time, so a lost response can be retried without toggling the state backward or reordering saved items. Save requests time out after 15 seconds and release controls for explicit retry. Returning from the browser back/forward cache reloads reader pages with GET to refresh saved state.
- History search accepts 2–200 Unicode characters. Whitespace-separated keywords must all match within one article, possibly in different fields. It shows highlighted titles and plain-text excerpts from descriptions or summaries, with source/date context. Up to 100 results are shown; an extra row detects truncation. Invalid input, unavailable storage, query failure, and no results have separate states.

---

## Domain Terms

**Feed** — normalised subscription feed returned by a route. Contains title, link, description, and a list of FeedItems.

**FeedItem** — one article, post, release, or notification. Fields: title, link, description, pubDate, author, categories, image. Route rules for filling them live in `AGENTS.md`.

**Briefing story** — a presentation group of articles with matching, sufficiently specific headlines within one category of a daily briefing. It retains every article's original link and independent reading identity; headline agreement does not establish that the articles describe the same event.

**Route** — a public HTTP path mapped to one RouteHandler. Examples: `/hn/:feed`, `/github/releases/:owner/:repo`, `/rss`.

**RouteHandler** — single-method interface that produces a Feed. One class per source. No annotations or framework magic.

**RouteRegistry** — explicit registry mapping route path patterns to RouteHandlers. Registered manually in `BuiltInRoutes` (the curated catalog in `CuratedRoutes`).

**RouteConfig** — persisted per-route configuration: enabled flag, cache TTLs, schedule (minutes or cron), sourcePath alias, feedUrl, alias, autoSummary and topic. Stored in `route-config.json`.

**Subscription** — an enabled, concrete RouteConfig with an update schedule. “添加 RSS 订阅” collects a name and HTTP(S) RSS/Atom URL in a modal, validates them, and saves a disabled, unscheduled mount before showing its source card. The user then subscribes and fetches from that card. `SubscriptionService` applies a chosen interval (or the registered Route's structured recommendation) only when no schedule exists, preserves existing settings on resume, and uses FetchScheduler for initial refresh. Browsing sources never enables or fetches them. Pausing preserves the schedule and stored articles. Source templates with unresolved parameters remain in the advanced category configuration.

**Template route** — a path pattern such as `/hn/:feed`, or the bare `/rss` route. It only serves as a source for mounts and is never enabled, fetched or browsed itself; `AppRuntime.isTemplate` decides, and the admin endpoints reject toggling or fetching one.

**Mount alias** — a fixed path that delegates to a parameterised route. Example: `/my-hn` → sourcePath `/hn/top`. Created via the `/index` UI.

**Curated social source** — a concrete, initially disabled subscription (Telegram channels in `科技资讯`, X accounts in `AI` or `软件开发`). Telegram sources read the latest public `t.me/s/<channel>` preview through `FetchClient`, preserve post permalinks and source timestamps, and skip posts without readable text or link previews. `/telegram/channel/:channel` supports additional public channels through mounts. Fixed X sources share the existing `TwitterUserRoute` and credential store; their cards state that an X login Cookie is required. No new credentials, automatic subscriptions, or delivery settings are created by registering these sources.

**Generic RSS route** — the `/rss` route accepts an HTTP(S) RSS 2.0 or Atom feed URL via `RouteConfig.feedUrl`. It keeps valid public response bodies and validators in SQLite, reuses them after a `304`, and persists failure backoff across restarts.

**FetchClient** — shared source HTTP abstraction. `get()` returns a body with bounded transient retries; `fetch()` returns `FetchResponse(statusCode, headers, body)` for caller-managed policy. The default client limits each hostname to two active requests, including redirect destinations. It follows at most five redirects, strips extra headers across origins, and refuses HTTPS-to-HTTP redirects. Ollama model calls and Feishu delivery own separate HTTP clients; article extraction shares this source client.

**CacheService** — Caffeine-based in-memory TTL cache. Two levels: route-level feed cache and detail-page cache.

**FeedFetcher** — centralises the fetch → validate → persist → log cycle shared by the HTTP endpoint and the background scheduler. Empty feeds are failures; complete feeds are persisted before any response limit is applied.

**FeedFetchCoordinator** — owned by `AppRuntime`; shares an in-progress fetch, including item persistence and logging, between HTTP and scheduler callers with the same handler and full route context. It does not cache completed results. Changed source URLs use independent fetches.

**FetchScheduler** — checks per-route cron or minute-interval schedules every 60 seconds. Manual and scheduled fetches share an in-flight guard per route path. RSS retry deadlines take precedence over normal schedules, and manual refresh respects the same persisted backoff. Different paths can run concurrently. Fetching never waits for model inference and queues summaries only for `autoSummary` routes.

`FetchProgress` reserves each path before queuing work and records running, saved, failed, deferred, or unconfirmed outcomes. Success requires a new successful persistence log; a returned feed alone does not prove storage succeeded. Subscription cards poll only while running, use persistent logs and current-feed retry state after restart, and link saved content to the existing unread reader. Changing a source while its old fetch is running requires waiting for that fetch to end before refreshing the new source.

**FeedStore** — SQLite persistence: opening, transactions, and saving fetched articles. Everything else lives in modules reached through it that share its single connection and lock: `items()` article queries, `unread()` the unread inbox, `reading()` bookmarks and read state, `accounts()` users, `aiCache()` summaries and extracts, `summaryQueue()` the summary queue, `picks()` triage labels and feedback, `searchIndex()` FTS5 search, `fetchState()` fetch logs, health, HTTP snapshots and refresh state, and `radar()` radar runs and jobs. `FeedSchema` creates and migrates the core tables. Deleting an account clears its reading state in the same transaction.

**FeishuPusher** — optionally sends items immediately per route or as scheduled category digests. Progress advances only after every page is confirmed by HTTP and business status. Partial failure can repeat already delivered pages on retry; cursors are in memory and restart uses a 24-hour lookback.

**OllamaClient** — shared generation facade for explicit Ollama, OpenAI Chat Completions and Anthropic Messages adapters. The active platform is read from `AiConfigStore` on every call; all generation shares one interruptible permit. `ai-config.json` stores multiple named platforms and one global AI switch. Old host/model/enabled files migrate in memory and are written in the new format at the next save. Model identity includes protocol, endpoint and generation options, while credentials remain out of radar snapshots and cache keys. See [AI platform configuration](docs/ai-platforms.md).

**ArticleExtractor** — used by background summary and radar generation when saved article content is insufficient. Reuses cached extracts; otherwise fetches HTML through the shared `FetchClient` and removes navigation and active content with JSoup. Specific article-body containers take precedence over broad article/main regions. A recognized container with more than 200 Unicode code points supplies text directly with paragraph breaks, capped at 4,000 code points and cached in SQLite with model marker `html`. Only when no qualifying container is found does the existing AI cleanup run; its output is cached with the generating model's identity.

**ArticleSummarizer** — owns a single worker for the durable queue in `ai_summary_runs`. Summaries are on demand: a reader's click is *interactive* priority and runs before *background* work, which comes only from routes with `autoSummary` (at most 5 new items per scheduled fetch) or the admin batch action. Background tasks are dropped after 24 hours or once anyone reads the article. Only saved article URLs can enter the queue; duplicate queued/running work and cached summaries are skipped, and asking for a queued background task promotes it. It reuses substantial saved descriptions, then cached/model-assisted extracts, before generating a 2–3 sentence Chinese summary. Interactive tasks also run their model calls ahead of background AI work through `OllamaClient.interactive()`. Network errors and AI 5xx responses are retried once; other errors require manual retry. Disabling AI pauses queued work. At startup interrupted interactive tasks are requeued and interrupted background tasks become retryable errors. This queue assumes one application instance.

**ArticleTriage** — labels at most three 必知 (must-know) and three 精选 (worth reading) articles per category over the last 24 hours. The model chooses within a batch of titles and excerpts rather than scoring articles one by one; categories over 30 articles run a tournament. A background pass runs every five minutes, at most once per 30 minutes per category, and only for categories with articles not yet triaged under their current category. Labels and reasons live in `item_picks`.

**Pick feedback** — readers rate each label 准/不准 in `pick_feedback`, keyed by user. A category's recent verdicts are shown to the model as examples of the readers' taste, a label any reader rejected is never re-applied to that article, and the reader page shows the reader's 30-day agreement rate.

**Topic (category)** — every route belongs to one `app.core.Topic` such as 时事要闻 or 软件开发; labels are what users see and config files store. A route or mount may set its own `category`; otherwise it inherits the category of the registered route serving it. `AppRuntime.categoryOf` is the only resolver, used by the reader, triage, Feishu and radar. `Topic.RENAMED` records the regrouping from language/platform categories to topics; Feishu rules saved under an old name move to its successors on load.

---

## Data Flow

```
HTTP Request
  → RssLiteResource / ReaderResource
    → RouteConfigStore   — enabled check, TTL, feedUrl resolution
    → RouteRegistry      — path-pattern matching → RouteMatch
  → CacheService       — return cached Feed if fresh
    → FeedFetcher        — shared in-flight work through FeedFetchCoordinator
      → RouteHandler     — fetch + parse upstream (via FetchClient + JSoup/Jackson)
        → GenericRssRoute — conditional request, snapshot validation, retry state
      → FeedStore        — upsert items, write fetch_log entry
  → FeedRenderer       — RSS 2.0 (RssRenderer) or JSON (JsonRenderer)
  → HTTP Response

Background
  → FetchScheduler       — ticks every 60 s, triggers FeedFetcher per schedule
    → FeishuPusher       — immediate delivery after persistence
    → ArticleSummarizer.enqueueLinks — only for autoSummary routes, at most 5 new items
  → ArticleSummarizer worker — claims one queued article at a time, interactive first
    → saved description or ArticleExtractor cache/extraction
    → model summary → SQLite cache and terminal status
  → ArticleTriage        — every 5 min, labels 必知/精选 per topic → item_picks
  → FeishuPusher         — checks category digest schedules each tick
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
    List<String> categories,
    String image          // cover URL; null when the source has none
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
| `autoSummary` | boolean | Queue background summaries for up to 5 new items per scheduled fetch (default false) |
| `category` | string | Topic override; absent inherits the source route's category |

---

## Caching Strategy

- Route-level cache TTL: configurable per route, default 5 minutes.
- Detail-page cache TTL: configurable per route, default 30 minutes.
- Route cache keys include path, source path, feedUrl, and route/detail TTL settings.
- Format and item limit apply only to responses; RSS/JSON and different limits reuse the complete cached Feed. Offline responses also apply the requested limit.
- Concurrent loads of the same cache key are coalesced. TTL starts when loading completes.
- Error responses are not cached.
- `/rss` stores the body and ETag/Last-Modified together, keyed by the complete feed URL. A 304 without a valid snapshot triggers at most one unconditional request. Invalid replacement content never replaces a valid snapshot.
- Responses with `no-store`, `private`, `Vary`, or `Set-Cookie` delete any saved HTTP snapshot. Article persistence remains independent of this HTTP cache policy. Cross-origin redirects omit validators, so those feeds may require full responses.
- RSS failures retry after 60 seconds, doubling to at most one hour; a valid `Retry-After` can extend this deadline. Cooldown checks do not issue HTTP requests or add fetch-log failures. Success clears the backoff. Scheduled retries run on the first 60-second tick at or after the deadline.
- Legacy `get()` retries network failures and most 5xx responses at most three times, with 250/500 ms waits. 429 and 503 return immediately to the calling route's policy.

---

## Persistence (SQLite)

All data lives in a single SQLite file (default `feed-store.db`, overridable via `-Dfetch-news.db`).

Tables: `users`, `feed_items` (including the `image` cover URL), `bookmarks`, `read_items`, `ai_extracts`, `ai_summaries`, `ai_summary_runs`, `item_picks`, `pick_feedback`, `fetch_log`, `http_feed_snapshots`, `feed_refresh_states`, `search_index_state`, and the `feed_search_fts` virtual table. General route health is computed from fetch logs.

HTTP snapshots are keyed by feed URL. Refresh state is keyed by `(route_path, feed_url)`, so an older request finishing after a source change cannot erase the new source's backoff. It records check/success/change times, HTTP status, UTF-8 response-body size, duration, failures, error, and retry deadline. Response-body size is measured after text decoding, not wire traffic. A per-subscription SHA-256 fingerprint of the parsed feed tracks content changes independently of reusable HTTP snapshots, including sources that disallow caching and aliases sharing one feed URL.

Items are deduplicated globally by URL; the first source retains ownership of `route_path`. The recent reader query is capped at 500 items. Search indexes title, description, and summary with FTS5.

`FeedStore.searchHits` keeps unicode61 word matching for ordinary non-Han letter/number keywords, including Latin diacritic folding. Han-containing or punctuation-containing keywords use literal `instr` conditions with ASCII case folding. All conditions are combined with AND; SQL and FTS operators in input are never executed as syntax. Literal title hits rank first, followed by weighted FTS relevance where available and insertion ID. Every public database operation uses the same store monitor, so readers on its shared JDBC connection cannot observe an in-progress write transaction. Literal-only queries scan saved fields; mixed queries first narrow candidates through FTS. The current index is reused on normal startup. English embedded directly within continuous Chinese remains subject to unicode61 token boundaries.

Search initialization records a version in `search_index_state`. An absent or different version, a missing FTS table, or the legacy global-link deduplication migration recreates and fills the index from canonical articles and summaries. Schema initialization, legacy migration, index creation, and its version marker commit together. A failed initialization leaves the previous database state intact and closes the connection. This is versioned initialization, not a general repair service for arbitrary manual database changes or corruption.

Each `saveItems` batch commits article updates and its index refresh atomically. `saveSummary` likewise commits summary content, the initial success status when no run exists, and its index refresh together; existing queue run status remains managed by the worker. SQL or runtime failures roll back the operation, preserving previously saved content and searchable terms. If rollback or connection reset fails, the store closes that connection rather than risking an accidental partial commit.

The unread inbox uses `FeedStore.loadUnreadPage`: valid items without a matching URL in `read_items`, ordered by immutable insertion ID descending, with `id < before` continuation. It reads one extra row to detect the next page; new inserts do not shift existing pages. Source attribution remains the first source's path. Counts ignore the cursor, and the source menu covers all unread items. Keyword filtering is a literal substring query separate from ranked FTS search. Batch read writes use a single atomic insert, including on failure.

The container is stateless; the file is host-mounted at `./data/feed-store.db` in Docker.

---

## System Properties

| Property | Default | Purpose |
|---|---|---|
| `fetch-news.db` | `./feed-store.db` | SQLite database path |
| `fetch-news.route-config` | `./route-config.json` | Route configuration file |
| `fetch-news.ai-config` | `./ai-config.json` | AI platforms and the global AI switch |
| `fetch-news.feishu-config` | `./feishu-config.json` | Feishu webhook and per-category delivery settings |
| `fetch-news.credential-config` | `./data/credential-config.json` | Saved Bilibili / X cookies |
| `fetch-news.auth.enabled` | `true` | Token authentication (`FETCH_NEWS_AUTH_ENABLED`) |
| `fetch-news.admin-token` | — | Pins/resets the owner login token (`FETCH_NEWS_ADMIN_TOKEN`) |

---

## Non-Goals (current version)

- External identity providers, passwords, or self-registration — accounts are created by an admin
- Distributed cache or external queue
- Browser automation
- Dynamic plugin loading or classpath scanning
- Full RSSHub common-parameter compatibility (Radar rules, OpenCC, custom filters)
- Pagination in route handlers

## Radar reports

The `app.radar` pipeline (shown to readers as 精选日报) snapshots a day's saved articles, the Profile, configuration and model identity, then scores, deduplicates, selects and enriches articles into immutable reports. It is built in rather than configurable: `RadarConfig.BUILT_IN` scores every source with the shipped `tech-news` Profile (`BuiltInProfiles`, read from the jar), runs daily at 08:00 Asia/Shanghai for the previous day, and is available exactly when AI is enabled. Readers can also generate a report on demand at `/reader/radar`; page loads and Markdown exports only read saved output. A durable single-instance worker shares the AI generation permit with summaries. Every run keeps a snapshot of the `RadarConfig` it used, so the record keeps its full shape.

`CommunityContextService` fetches bounded Hacker News story/comment context on explicit request, validates the article URL association, and stores attributed plain-text context in `community_context`; its jobs persist in `radar_jobs`. See [精选日报](docs/radar.md) for behaviour, budgets and recovery.
