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
| `/reader` | Digest reader — recent items grouped by source |
| `/reader/unread` | Persistent unread inbox — all retained unread items, filtered and paged |
| `/reader/article?link=…` | Saved-content reading page — source text, labeled cached AI content, and explicit read/bookmark actions |
| `/reader/bookmarks` | Saved bookmarks |
| `/reader/search` | History search across saved titles, descriptions, and AI summaries |
| `/reader/briefing` | Daily briefing grouped by category |
| `/<route-path>` | RSS 2.0 or JSON feed output |

All pages are publicly accessible — there is no authentication layer.

### UI behavior

- `admin.css` provides the shared warm-paper palette, responsive layouts, visible keyboard focus, and reduced-motion support.
- `ui.js` owns safe preference storage, transient status messages, progressive reveal, and reading progress. `reader.js` owns reader filters, actions, source navigation, and keyboard shortcuts. `admin.js` owns the HTMX navigation shell, mobile drawer, and modal focus management.
- Assets are explicitly served by `FrontendAssetResource`; new scripts must be registered there because feed paths use a catch-all controller.
- Recent-reader filters combine time-range content with category, title, and read/bookmark state. The read filter defaults to unread; read filter and layout preferences persist locally. In that view, “Mark all read” applies to every loaded item in the selected time range, including items hidden by client-side filters.
- The separate unread inbox filters the complete retained unread set by source, bookmark, and literal keyword before loading 50 items. Keywords match titles, descriptions, and saved summaries, including Chinese substrings. Next-page links preserve all filters. Counts cover matching unread articles across all pages; bulk read actions affect only the current page. The inbox shares layout preferences without changing the digest's saved read filter.
- Time, category, and bulk actions live in a native disclosure showing the selected range and category when closed. Search and read-state filters remain visible; mobile article actions use 44-pixel targets with explicit read-state labels.
- `reader/fragments.html` shares article cards between the digest and inbox, and summary status across the reader, search, and briefing. Reading pages show a compact AI summary, running, or failure label; timing diagnostics remain in the admin views.
- Summary submission returns queued/running JSON immediately. Reader cards and admin item rows poll read-only status every two seconds, stop at completion, and restore pending work after reload. Connection failures offer a status check instead of silently repeating a generation request. Cached summaries remain visible with AI disabled.
- Read and bookmark requests prevent duplicate submissions and restore the previous state on failure. Bookmark deletion removes a card only after a successful response. Admin navigation ignores stale responses and commits its title and selection only after a successful content swap.
- Reader shortcuts: `j`/`k` move through visible articles; `o` opens and marks an article read; `b` toggles bookmark; `r` toggles read state; `?` opens the shortcut hint. Form fields and modified key presses keep their native behavior.
- In the inbox, `r` and the read button mark articles read idempotently, including from stale tabs. Removing a card advances keyboard focus to the next article or the page-completion link. Read/bookmark saves block same-tab navigation and filter submission until the request finishes; failures restore cards and counts.
- All five reading lists expose a secondary “站内阅读” link; their existing external titles and `o` shortcut retain their behavior. Client-enhanced detail links preserve the current list path, query, and fragment as `returnTo`. The server accepts only exact reader list paths for that return destination.
- Article detail GETs are read-only and load one `StoredArticle` snapshot from SQLite. The original description takes precedence over cached AI-extracted text; AI-derived content is explicitly labeled, and a missing body is distinct from a load error. Cached summaries stay visually separate. No article or model requests run while opening this page.
- `ArticleContent` is the only HTML rendering boundary for saved bodies: JSoup keeps readable structure and HTTP(S) links, strips active content and external media, and renders cached AI extracts as literal text. Reading saved content causes no automatic third-party resource request.
- Detail pages remember three font sizes locally. Explicit completion marks one URL read idempotently and returns only after persistence succeeds. Detail bookmark PUT requests set a desired state using canonical article metadata and preserve the original bookmark time, so a lost response can be retried without toggling the state backward or reordering saved items. Save requests time out after 15 seconds and release controls for explicit retry. Returning from the browser back/forward cache reloads reader pages with GET to refresh saved state.
- History search accepts 2–200 Unicode characters. Whitespace-separated keywords must all match within one article, possibly in different fields. It shows highlighted titles and plain-text excerpts from descriptions or summaries, with source/date context. Up to 100 results are shown; an extra row detects truncation. Invalid input, unavailable storage, query failure, and no results have separate states.

---

## Domain Terms

**Feed** — normalised subscription feed returned by a route. Contains title, link, description, and a list of FeedItems.

**FeedItem** — one article, post, release, or notification. Fields: title, link, description, pubDate, author, categories.

**Route** — a public HTTP path mapped to one RouteHandler. Examples: `/hn/:feed`, `/github/releases/:owner/:repo`, `/rss`.

**RouteHandler** — single-method interface that produces a Feed. One class per source. No annotations or framework magic.

**RouteRegistry** — explicit registry mapping route path patterns to RouteHandlers. Registered manually in `AppRuntime`.

**RouteConfig** — persisted per-route configuration: enabled flag, cache TTLs, schedule (minutes or cron), sourcePath alias, feedUrl. Stored in `route-config.json`.

**Mount alias** — a fixed path that delegates to a parameterised route. Example: `/my-hn` → sourcePath `/hn/top`. Created via the `/index` UI.

**Generic RSS route** — the `/rss` route accepts an HTTP(S) RSS 2.0 or Atom feed URL via `RouteConfig.feedUrl`. It keeps valid public response bodies and validators in SQLite, reuses them after a `304`, and persists failure backoff across restarts.

**FetchClient** — shared source HTTP abstraction. `get()` returns a body with bounded transient retries; `fetch()` returns `FetchResponse(statusCode, headers, body)` for caller-managed policy. The default client limits each hostname to two active requests, including redirect destinations. It follows at most five redirects, strips extra headers across origins, and refuses HTTPS-to-HTTP redirects. Ollama model calls and Feishu delivery own separate HTTP clients; article extraction shares this source client.

**CacheService** — Caffeine-based in-memory TTL cache. Two levels: route-level feed cache and detail-page cache.

**FeedFetcher** — centralises the fetch → validate → persist → log cycle shared by the HTTP endpoint and the background scheduler. Empty feeds are failures; complete feeds are persisted before any response limit is applied.

**FeedFetchCoordinator** — owned by `AppRuntime`; shares an in-progress fetch, including item persistence and logging, between HTTP and scheduler callers with the same handler and full route context. It does not cache completed results. Changed source URLs use independent fetches.

**FetchScheduler** — checks per-route cron or minute-interval schedules every 60 seconds. Manual and scheduled fetches share an in-flight guard per route path. RSS retry deadlines take precedence over normal schedules, and manual refresh respects the same persisted backoff. Different paths can run concurrently; saved articles enter the shared summary queue after feed persistence and immediate delivery. Fetching does not wait for model inference.

**FeedStore** — SQLite persistence layer. Owns feed items, read state, bookmarks, AI caches and run status, fetch logs, HTTP feed snapshots, RSS refresh state, and the FTS5 index. General route health is derived from recent fetch logs; the RSS status table shows the current source's persisted refresh state.

**FeishuPusher** — optionally sends items immediately per route or as scheduled category digests. Progress advances only after every page is confirmed by HTTP and business status. Partial failure can repeat already delivered pages on retry; cursors are in memory and restart uses a 24-hour lookback.

**OllamaClient** — shared generation facade for explicit Ollama, OpenAI Chat Completions and Anthropic Messages adapters. The active platform is read from `AiConfigStore` on every call; all generation shares one interruptible permit. `ai-config.json` stores multiple named platforms and one global AI switch. Old host/model/enabled files migrate in memory and are written in the new format at the next save. Model identity includes protocol, endpoint and generation options, while credentials remain out of radar snapshots and cache keys. See [AI platform configuration](docs/ai-platforms.md).

**ArticleExtractor** — called only inside the summary worker when the saved description is short or absent. Reuses cached extracts; otherwise fetches HTML through the shared `FetchClient`, cleans it with JSoup, and uses Ollama to extract the article body once. Results are cached in SQLite.

**ArticleSummarizer** — owns a single worker for reader, admin, batch, and scheduled requests. Only saved article URLs can enter the durable queue in `ai_summary_runs`; duplicate queued/running work and cached summaries are skipped. It reuses substantial saved descriptions, then cached/model-assisted extracts, before generating a 2–3 sentence Chinese summary. Each model request snapshots its current configuration; saved metadata identifies the model used for generation. Errors require explicit manual retry. Disabling AI pauses queued work. At startup queued tasks remain queued; interrupted running tasks become retryable errors unless a summary was already saved. This queue assumes one application instance.

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
    → ArticleSummarizer.enqueue — records saved URLs in the durable queue
  → ArticleSummarizer worker — claims one queued article at a time
    → saved description or ArticleExtractor cache/extraction
    → Ollama summary → SQLite cache and terminal status
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

Tables: `feed_items`, `bookmarks`, `read_items`, `ai_extracts`, `ai_summaries`, `ai_summary_runs`, `fetch_log`, `http_feed_snapshots`, `feed_refresh_states`, `search_index_state`, and the `feed_search_fts` virtual table. General route health is computed from fetch logs.

HTTP snapshots are keyed by feed URL. Refresh state is keyed by `(route_path, feed_url)`, so an older request finishing after a source change cannot erase the new source's backoff. It records check/success/change times, HTTP status, UTF-8 response-body size, duration, failures, error, and retry deadline. Response-body size is measured after text decoding, not wire traffic. A per-subscription SHA-256 fingerprint of the parsed feed tracks content changes independently of reusable HTTP snapshots, including sources that disallow caching and aliases sharing one feed URL.

Items are deduplicated globally by URL; the first source retains ownership of `route_path`. The recent reader query is capped at 500 items. Search indexes title, description, and summary with FTS5.

`FeedStore.searchHits` keeps unicode61 word matching for ordinary non-Han letter/number keywords, including Latin diacritic folding. Han-containing or punctuation-containing keywords use literal `instr` conditions with ASCII case folding. All conditions are combined with AND; SQL and FTS operators in input are never executed as syntax. Literal title hits rank first, followed by weighted FTS relevance where available and insertion ID. Every public database operation uses the same store monitor, so readers on its shared JDBC connection cannot observe an in-progress write transaction. Literal-only queries scan saved fields; mixed queries first narrow candidates through FTS. The current index is reused on normal startup. English embedded directly within continuous Chinese remains subject to unicode61 token boundaries.

Search initialization records a version in `search_index_state`. An absent or different version, a missing FTS table, or the legacy global-link deduplication migration recreates and fills the index from canonical articles and summaries. Schema initialization, legacy migration, index creation, and its version marker commit together. A failed initialization leaves the previous database state intact and closes the connection. This is versioned initialization, not a general repair service for arbitrary manual database changes or corruption.

Each `saveItems` batch commits article updates and its index refresh atomically. `saveSummary` likewise commits summary content, the initial success status when no run exists, and its index refresh together; existing queue run status remains managed by the worker. SQL or runtime failures roll back the operation, preserving previously saved content and searchable terms. If rollback or connection reset fails, the store closes that connection rather than risking an accidental partial commit.

The unread inbox uses `FeedStore.loadUnreadPage`: valid items without a matching URL in `read_items`, ordered by immutable insertion ID descending, with `id < before` continuation. It reads one extra row to detect the next page; new inserts do not shift existing pages. Source attribution remains the first source's path. Counts ignore the cursor, and the source menu covers all unread items. Keyword filtering is a literal substring query separate from ranked FTS search. Batch read writes use a single atomic insert, including on failure.

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
| `fetch-news.feishu-config` | `./feishu-config.json` | Feishu webhook and per-category delivery settings |

---

## Non-Goals (current version)

- Authentication or multi-user support
- Distributed cache or external queue
- Browser automation
- Dynamic plugin loading or classpath scanning
- Full RSSHub common-parameter compatibility (Radar rules, OpenCC, custom filters)
- Pagination in route handlers

## Radar reports

The optional `app.radar` pipeline snapshots saved articles, source provenance, Profiles, configuration and model identity, then routes, scores, deduplicates, selects and enriches articles into immutable reports. `/reader/radar` and Markdown exports only read saved output. A durable single-instance worker shares Ollama generation admission with existing summaries. See [radar operations](docs/radar.md) for configuration, budgets, recovery and quality limits.

Radar administration lives in separate resources: `/index#radar` edits Profiles and policy; `/index#radar-tasks` reads durable runs and observed generation metrics; `/index#radar-evaluation` submits a fixed five-call sample evaluation against a saved AI platform without activating it. Profile writes use versioned prompt files plus atomic manifests. `radar_jobs` persists evaluation, delivery and community jobs, recovering interrupted work to an explicit paused state.

`RadarDeliveryService` sends immutable saved reports through the existing default Feishu webhook, with per-page receipts and explicit confirmation for uncertain retries. Automatic discovery is separately opt-in. `CommunityContextService` uses the runtime FetchClient for bounded Hacker News story/comment retrieval, validates the article URL association, and stores attributed plain-text context independently in `community_context`. Reader page loads do not initiate upstream requests; community retrieval is explicit. See [radar operations](docs/radar.md) for controls and limits.
