# Find saved articles with Chinese keywords

The existing search uses unicode61 word tokens. Continuous Chinese titles such as 本地模型部署指南 are not found by 模型; current tests only put spaces around the Chinese keyword. Search errors also appear as empty results, and matching descriptions are never shown.

## Delivery contract

- Search saved titles, descriptions, and AI summaries across all retained articles, including read articles. Keep global URL identity and existing first-source ownership.
- Whitespace-separated keywords must all match, potentially in different fields. Ordinary non-Han letter/number keywords keep FTS word matching. Han-containing and punctuation-containing keywords use literal substring matching, with ASCII case folding and no SQL/FTS operator interpretation. Unicode whitespace and supplementary Han characters are handled explicitly.
- Prefer title matches, then existing FTS relevance where applicable, then stable insertion ID. Return at most 100 visible results; fetch one extra result to distinguish a truncated list from a complete result set.
- Show safe highlighted title text and a short excerpt from the matching description or saved summary. Escape all source and query text. Strip HTML from description excerpts.
- Blank search shows guidance; invalid queries, unavailable storage, and query failures have distinct error responses. Accept 2–200 Unicode characters for a submitted query.
- Keep the current index and schema. Literal-only queries scan saved fields; measure that cost rather than claiming an indexed Chinese search. No new dependency or external search service is required.

## Verification

Use incremental public-API tests for Chinese short terms, multi-field intersections, English compatibility, punctuation, stable title preference, updated content/summary, duplicate URLs, and restart. Freeze 30 synthetic retrieval cases before implementation; report baseline and repaired recall separately from human relevance evaluation. Check error rendering, escaping, the 100-result boundary, and mobile/desktop interaction in an isolated preview. Run the full suite and build before completion.

Index versioning, startup rebuild removal, a trigram accelerator, backup/restore, and AI job recovery remain separate slices.

## Acceptance record — completed 2026-10-05

- A public store test first reproduced the two-character Chinese failure, then passed with the hybrid query. Follow-up tests cover Unicode whitespace-only input, supplementary Han characters, cross-field AND, literal title preference, rich hit data, duplicate URL updates, new/replaced summaries, read-article retrieval, and restart. All 30 fixed synthetic cases in `src/test/resources/search-scenarios.json` pass; these include negative queries and are not human relevance judgments.
- View/controller tests verify source text escaping, HTML description cleaning, bounded excerpts, supplementary characters, Latin diacritic folding, word boundaries, blank/invalid/error states, exact 100 versus 101 results, and the public stylesheet/HTTP endpoints.
- Independent review identified three reproducible issues, each repaired with a failing regression first: long keywords expanded excerpts beyond 140 characters; ASCII whitespace titles produced unnamed links; FTS `cafe` hits in `CAFÉ` summaries selected unrelated body text. Excerpts now preserve original glyphs and code-point boundaries, with up to 140 content characters plus two ellipses.
- `./gradlew test bootJar --warning-mode all` passes **226 tests in 29 suites**, with zero failures, errors, or skips. `git diff --check` and the existing reader script syntax check pass. Existing SQLite native-access and test-agent warnings remain non-failing.
- Browser checks verify the 30-case corpus, title/body/summary contexts, input errors, 100-result truncation, unsafe source/query text escaping, Enter submission, back navigation, and native form validation. Eight states at four widths (320, 390, 768, 1440) pass **32 responsive checks**, with no horizontal overflow or script errors. Isolated preview fixtures and screenshots live under `build/search-preview/`; no real article database or external AI/delivery service is used.

## Measured cost and remaining boundaries

The final compiled `FeedStore.searchHits(query, 101)` was measured on 100,000 synthetic articles of approximately 276 characters, using Java 25 and the project's SQLite JDBC 3.47.1.0. Seven measured runs follow one warm-up; timing includes JDBC reads and hit construction, but excludes HTTP, template rendering, cold-cache reads, and concurrent workers.

| Query | Warm median | Warm maximum |
|---|---:|---:|
| 模型 | 171.026 ms | 178.245 ms |
| 知识库 | 156.942 ms | 157.316 ms |
| 恢复 (summary) | 167.916 ms | 172.709 ms |
| 不存在 | 146.148 ms | 147.402 ms |
| SQLite | 0.957 ms | 1.054 ms |
| SQLite 知识库 | 1.072 ms | 2.088 ms |
| 模型 恢复 | 167.306 ms | 169.432 ms |

The probe and raw samples are in `build/search-benchmark/`. The measurement makes the scan cost visible; it does not establish a production latency target. Longer descriptions, more keywords, and concurrent writes need separate measurement. Index initialization still rebuilds existing FTS data at startup.

This design retains unicode61 English token boundaries: `Ollama` does not match `使用Ollama在…`, while it matches `使用 Ollama 在…`. Literal matching folds ASCII case only. Full-width normalization, synonyms, and general multilingual segmentation are outside this slice. The excerpt word matcher approximates unicode61 with Unicode word tokens and Latin diacritic folding; rare tokenizer-specific normalization differences may still yield an unhighlighted match.

The tokenizer choice follows verified constraints: default word tokenization does not split continuous Chinese, and trigram MATCH cannot recover two-character terms on its own. See [SQLite unicode61](https://www.sqlite.org/fts5.html#unicode61_tokenizer) and [SQLite trigram](https://www.sqlite.org/fts5.html#the_trigram_tokenizer). The hybrid query is this project's scope choice, not a recommendation to replace indexed search at arbitrary scale.
