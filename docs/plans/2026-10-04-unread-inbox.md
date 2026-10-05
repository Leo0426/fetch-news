# Persistent unread inbox

Add a separate `/reader/unread` entry so every retained unread article is reachable independently of the digest's time window and 500-item cap.

## Delivery contract

- Query valid, globally unique stored articles that have no read mark. Order by immutable insertion ID descending and continue with `id < before`; new articles appear when returning to the start.
- Render 50 items per page. Fetch one extra row only to detect continuation. Counts cover the complete matching unread set; page actions cover only loaded articles.
- Apply exact source, optional bookmark, and literal keyword filters in SQLite before pagination. Keyword matching includes titles, descriptions, and saved summaries, including Chinese substrings. This is a bounded inbox scan, not a replacement for FTS ranking.
- Keep source attribution to the first source that stored a URL. Reuse existing summaries, bookmark/read actions, layout preference, and keyboard navigation. Inbox state must not inherit the digest's saved read filter.
- Preserve all filters in next/start links. Malformed cursors return 400; unavailable storage and query failures display an error rather than a false empty inbox.
- Make page-wide read writes atomic, with no change to shared-connection transaction mode.

## Implementation and verification

1. Database query and real SQLite behavior tests: old items, over 500 items, ties, changing rows between pages, scoped count, bookmarks, Chinese literals, invalid values, and restart.
2. Thin reader endpoint, view model and template: batch summary loading, bounded initial bookmark state, safe URLs, and error handling.
3. Reuse article card rendering and reader actions; add explicit inbox counters, page completion and next-page affordances. Keep batch changes local to the displayed page.
4. HTTP tests, existing regression suite, independent review, and isolated browser checks with more than one page of fixtures.

No user database migration, external service call, deployment, or publishing is needed for this slice. Existing tables supply the persisted queue.

## Acceptance record — 2026-10-04

- Store tests cover 1,200 items with identical timestamps, new inserts and read changes between pages, articles older than seven days, source/bookmark/Chinese keyword intersections, literal SQL-like input, restart persistence, and first-source ownership. A forced SQLite failure verifies that a batch read cannot partially commit.
- Controller/template tests cover 50/5 continuation, exact filter preservation for Chinese and reserved URL characters, escaped titles, read-page continuation, invalid cursors, unavailable storage, and distinct empty/error states. HTTP tests cover the new page, stylesheet, and invalid numeric cursors.
- Independent review found two interaction gaps, both corrected: stale tabs now mark read idempotently, and pending read/bookmark saves prevent same-tab navigation or filter submission until success or rollback.
- An isolated browser fixture contains 160 synthetic articles across 30 days, including 147 unread and 103 matching a source/bookmark/Chinese keyword intersection. Eight behavioral scenarios pass: complete 50/50/47 traversal, preserved digest preference, 50/50/3 filtered traversal, failure rollback, keyboard continuity, page-only bulk writes, stale-tab idempotency, and pending navigation protection.
- Five reader pages at 320, 390, 768, and 1440 pixels pass 20 responsive checks without script errors or horizontal overflow. Extra space below inbox pagination fixes feedback obscuring the next-page action; actual pointer clicks pass at 320, 390, and 1440 pixels. Browser evidence and scripts are under `build/unread-preview/`; synthetic data is restored after testing.

The full regression run also exposed an existing mixed-precision timestamp comparison in Feishu's store queries. Fixed timestamps reproduce both repeated old items and missing new items; the repair uses a broad SQL second boundary followed by exact `Instant` filtering and ordering, without migrating stored data or discarding nanosecond precision.

Final verification: `./gradlew test bootJar --warning-mode all` succeeds with **209 tests across 26 suites**, zero failures, errors, or skips. The eight fixed-time boundary tests failed before the repair and pass afterward, together with the existing Feishu delivery tests. `git diff --check` and syntax checks for `reader.js`, `ui.js`, and `admin.js` also pass. Java's existing SQLite native-access and test-agent class-sharing warnings remain non-failing.

Remaining roadmap work includes category mapping, ranked Chinese FTS evaluation, consistent backup/restore, and measurements with 100,000 articles. This slice does not establish production latency or model-quality results.
