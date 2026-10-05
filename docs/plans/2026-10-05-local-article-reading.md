# Read saved articles inside fetch-news

## Problem and decision

The reader, unread inbox, search, bookmarks, and briefing expose external titles and brief summaries, even though the database already stores article descriptions and cached extracts. Search can find a passage that the UI cannot display. This is a code-observed gap; reduced tab switching and easier reading are expected benefits, not measured user outcomes.

Deliver a saved-content reading page as an extension of the existing reader. Keep external titles and shortcuts unchanged and add a secondary “站内阅读” link. A standalone page offers a stable URL, normal browser navigation, and useful content without JavaScript. A modal would add focus, history, and dismissal complexity without improving this first slice. Automatic full-text fetching is excluded: saved content may be a snippet, and no network/model work should be hidden behind a read-only request.

## Contract

- `GET /reader/article?link=...&returnTo=...` reads canonical content, source metadata, cached summary/extract, bookmark and read state from SQLite. GET changes no read/bookmark/AI state. No upstream or model calls.
- Show source description first. When it has no useful text, show cached model-extracted text with an explicit AI provenance label. Otherwise show a clear missing-content state and a safe original link. A summary is always visually separate from saved article content.
- Sanitize source HTML through one JSoup allowlist renderer before its only unescaped template insertion. Preserve readable paragraphs/headings/lists/code/tables and HTTP(S) links. Remove scripts, event/style attributes, forms, embedded documents, and remote media; no third-party request is caused by displaying saved content. AI extracts remain literal text.
- Unknown article 404, invalid parameters 400, missing store 503, query failure 500. Error pages retain a safe return path and, for a valid HTTP(S) source URL, an original link. Orphan bookmarks must not disappear just because there is no retained article row.
- Return paths allow only exact reader list routes; preserve query and fragment while rejecting external, protocol-relative, backslash/control-bearing, or unexpected paths. Client-enhanced entry links capture the current list URL, including unread cursor/filter or search query. Without JavaScript links still open a usable page.
- Explicit read completion uses the existing idempotent read-all endpoint for one URL, then navigates only after successful persistence. Opening or closing the page does not mark read. Bookmark toggling is explicit, prevents double submission, and restores visible state on failure.
- Small/standard/large font preferences persist locally; readable line width, mobile targets, visible keyboard focus, and reduced motion follow the existing design. Do not load readerState on the detail page: its unread filter must not hide a completed article.

## Verification

Temporary SQLite and hostile-content fixtures cover the stored snapshot, canonical metadata, content provenance, URL handling, pure GET semantics and distinct errors. Browser checks cover list entry points, return filters, explicit read and bookmark persistence/failures, font preference, no-JS reading, unknown content, no unsolicited outbound requests, 320/390/768/1440 widths, keyboard access, and existing summary interactions. All previews use isolated fixtures; existing user data and running model are untouched.

## Follow-up

The independent health audit found that normal startup rebuilds FTS and writes/index refreshes are not atomic. That separate correctness/performance slice is recorded in `2026-10-05-search-index-follow-up.md` and remains a priority for the next iteration.

## Implementation and evidence

The page is implemented through `FeedStore.loadArticle`, `StoredArticle`, `ArticlePageView`, `ArticleNavigation`, and `ArticleContent`, with a thin reader controller and three static/template files. All five lists have secondary entry links; external titles and the existing summary queue keep their behavior. No schema migration or dependency was added.

The detail bookmark endpoint uses desired-state PUTs. Bookmark upserts now preserve the original `bookmarked_at` value while refreshing metadata, so retries do not reorder existing bookmarks. Client saves have a 15-second deadline covering both the response and its body; timeout releases controls and reports uncertain state rather than automatically sending another write.

TDD exposed and fixed source-content sanitization gaps, missing link handling, fallback provenance, and repeated-bookmark timestamp changes. A plain-text regression also found that generic type placeholders such as `List<T>` were treated as unknown HTML tags; the renderer now preserves unknown-only markup as escaped text while known HTML continues through the allowlist.

On 2026-10-05, `./gradlew test bootJar --console=plain` passed **266 tests in 40 suites**, with zero failures, errors, or skips, and produced the executable jar. Sixteen added tests cover the stored article snapshot, controller/state behavior, return paths, and content rendering. JavaScript syntax and `git diff --check` passed.

The isolated browser preview at port 51504 uses a new `build/article-preview/runtime`, disabled routes/AI/Feishu, and synthetic saved content. Initial browser verification passed 13 scenarios including all list entry points, preserved return queries/cursors/fragments, source/extract/summary/empty states, no-JavaScript reading, explicit read/bookmark changes, response-loss retries, bfcache returns, font memory, keyboard access, hostile HTML, and 320/390/768/1440 layouts. No external resource requests or browser JavaScript errors were observed. The only tables/code overflow stays inside the corresponding content block. Screenshots and runnable local checks are retained in `build/article-preview/`.

Final browser revalidation passed five additional checks after the fixes: exact generic-type text preservation, rich/hostile HTML regression, corrected empty-body wording, an accepted bookmark PUT whose response stalls until the 15-second deadline (explicit retry preserves both state and first bookmark time), and a stalled read POST that releases controls without automatically resending. Total browser evidence is **18 passing scenarios**, with zero external requests and zero JavaScript errors. The deadline checks advance the Playwright clock while exercising the real browser AbortController and fetch behavior. Final preview screenshots were refreshed and inspected at desktop/mobile sizes.
