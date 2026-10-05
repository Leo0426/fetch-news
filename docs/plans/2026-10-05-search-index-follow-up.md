# Search index initialization and atomic updates

Status: **implemented and verified, 2026-10-05**. This slice follows saved-content reading and addresses search persistence before further search scaling.

## Source evidence

Before this slice, `FeedStore.init()` always called `rebuildSearchIndex()` after schema initialization. The rebuild separately deleted and inserted every FTS row. `saveItems()` committed its item batch before calling `refreshSearchIndex()`, while `saveSummary()` wrote the summary and run status before refreshing the index. Incremental refresh also issued separate delete and insert statements. These operations did not share a transaction.

Implementation: [FeedStore](../../src/main/java/app/store/FeedStore.java), especially `initializeSchema`, `initializeSearchIndex`, `inTransaction`, `saveItems`, and `saveSummary`.

Consequently, each restart performed work proportional to stored content. A failed index refresh could leave saved content and word-search results inconsistent until another successful rebuild. Removing startup rebuilding alone would have made that inconsistency persist.

## Delivered contract

- `search_index_state` records index version 1 separately from application data. A matching version and existing FTS table reuse the index without rewriting it. Missing metadata, a version change, a missing table, or legacy item migration recreate the index from saved articles and summaries.
- Schema initialization, legacy global-link deduplication, FTS creation/backfill, and the version marker commit in one transaction. An initialization failure rolls all of them back and closes the connection.
- Each article batch and its incremental index refresh commit atomically. Saved summary content, its initial success status if no run exists, and index refresh also commit atomically. Existing queue run status remains owned by the worker.
- All public database methods share the store monitor because one JDBC connection otherwise exposes its uncommitted state to other threads. SQL and runtime failures roll back; failure to roll back or reset auto-commit closes the connection and preserves the original exception.
- Chinese literal matching, word matching, ranking, and first-source ownership remain unchanged. Normal upserts retain article IDs; the pre-existing legacy deduplication migration can assign new IDs, so its FTS rows are always rebuilt to match the resulting canonical rows.
- No new dependency or user configuration is required. First upgrade backfills the index once. Automatic recovery covers versioned initialization and a missing index, not arbitrary manual database edits or corruption. Concurrent application instances sharing one file remain outside the existing single-instance contract.

## Verification

All fixtures use temporary or build-directory SQLite files; the user's live database and source/model services were not accessed.

Nine new tests in [SearchIndexInitializationTest](../../src/test/java/app/store/SearchIndexInitializationTest.java) and [SearchIndexLifecycleTest](../../src/test/java/app/store/SearchIndexLifecycleTest.java) verify:

1. Old articles and cached summaries are searchable after first initialization.
2. Reopening works with index writes explicitly blocked; injected triggers survive, proving the index was not silently dropped and recreated.
3. Version changes replace stale terms, and a missing index is restored even with a current marker.
4. Legacy per-route deduplication retains the first source and timestamps, removes duplicates, and aligns FTS row IDs with canonical rows.
5. A failure writing the new version rolls back the rebuilt index and old marker; removing the fault allows complete retry.
6. An index write failure rolls back an entire article batch, including existing updates and new rows. Later store writes still commit normally.
7. Failed summary insertion leaves no summary or false success status; failed replacement retains the previous summary and status across restart. Explicit retry succeeds after removing the fault.

Faults are injected through SQLite triggers at the FTS storage/version boundary, with no production test hook. RED runs first demonstrated partially saved article content, partially saved summary content, and startup rewriting a current index. The corresponding transaction and initialization changes made each pass. Tests do not simulate process kill, filesystem corruption, disk exhaustion, or JDBC rollback/reset failure.

`./gradlew test bootJar --console=plain`: **275 tests, 42 suites, zero failures/errors/skips**, and executable JAR built. Existing search fixtures retain 30 synthetic query scenarios, including Chinese, English, mixed terms, punctuation, and summary-only terms. Independent review found no actionable defect in the delivered slice.

The existing isolated preview at port 51504 was restarted with the compiled implementation, preserving all 14 fixture articles, two read marks, three bookmarks, and their timestamps. Its previously unversioned index upgraded to version 1. Seven HTTP checks passed for English/Chinese/mixed search, saved detail content/state, and the unread list. All sources, AI, and Feishu remained disabled; evidence is in `build/article-preview/search-index-smoke.json`.

## Synthetic constructor measurement

Fixture: 100,000 articles with a 231-character base body plus a short suffix, and 10,000 summaries. The initial database has the global-link schema and no FTS index or version marker. Java 25.0.2, SQLite JDBC 3.47.1.0 / SQLite 3.47.1, macOS 27.0.1 aarch64, 16 logical processors.

| FeedStore constructor | Before | After |
| --- | ---: | ---: |
| First open, including initial index build | 1464.343 ms | 1471.994 ms |
| Reopen, median of 7 | 2758.196 ms | 0.745 ms |
| Reopen, maximum of 7 | 2848.372 ms | 1.163 ms |

Both runs passed 40 search checks across eight opens and ended with 100,000 indexed rows. Results measure the constructor in one process with warm OS caches, excluding fixture seeding, close, and search verification. They do not measure full application startup, production latency, memory consumption, or the older per-route migration. The first build still scales with stored content.

Local reproducibility artifacts: `build/search-index-probe/SearchStartupProbe.java`, `README.md`, and `baseline/results.json` / `final/results.json`; the pre-change compiled classes and class hashes are retained there. Build artifacts are intentionally untracked, while the measured conditions and results are preserved in this record.
