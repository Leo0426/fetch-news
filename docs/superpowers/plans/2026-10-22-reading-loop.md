# Continuous Reading Implementation Plan

> Implementation: continue in the current workspace, preserve existing work, and delegate independent changes. User selected the complete reading workflow and explicitly requested continued development.

**Goal:** Make subscription → saved article → summary → completed reading → bookmark/refinding a reliable, continuous workflow.

**Architecture:** Keep the existing Spring MVC, SQLite, Thymeleaf and Alpine flows. Fix the boundaries between existing screens instead of adding another reading subsystem. Use deterministic local RSS/AI fixtures for acceptance and leave personal configuration intact.

**Design:** Four observed gaps bound this iteration: non-idempotent list actions, lost reading context, inaccessible RSS creation from subscription discovery, and static briefing summary progress. Existing history search supplies the refinding step and must be exercised with saved summary text.

## Requirements and tasks

- [x] Reliable list actions: submit the desired read/bookmark state; bound request duration; retries after a lost response cannot invert the intended state. Reuse the article bookmark endpoint and preserve public legacy endpoints for compatibility. Verify persistence, duplicate requests, stale views and network failures.
  - Files: `ReaderResource.java`, `static/reader.js`, focused Java and Node tests.
- [x] Continuous return: remember list filters and article position within the browser tab; return using fresh server read/bookmark state; locate the next visible article when the completed one disappears. Include ordinary return, completion, and browser back. Storage denial and malformed saved data must degrade to normal navigation.
  - Files: `static/article.js`, `static/reader.js` initialization hooks, focused Node tests.
- [x] RSS entry: offer an explicit add action on subscription discovery, validate and persist a named RSS mount, and return to a source card that can subscribe/fetch and open unread articles. Reuse existing configuration logic where suitable, keep existing schedules unchanged, and show actionable errors.
  - Files: subscription controller/service/templates and associated tests; use existing modal infrastructure.
- [x] Briefing summaries: pending compact summaries update without reload; errors expose a recovery route; completed text stays available with AI disabled. No automatic regeneration or repeated submission from polling.
  - Files: shared summary fragment, `static/compact-summary.js`, briefing/summary tests.
- [x] End-to-end acceptance: isolated app and local fixtures cover empty state → add RSS → fetch → unread → article → summary → bookmark → complete → context restored → find in bookmarks/history search; reload/restart preserves durable states. Cover failed fetch/summary and response-loss retry, plus a narrow viewport.
- [x] Run relevant tests while developing, then full Java and Node suites and `bootJar`; inspect changes, update existing documentation, deploy an immutable local jar, verify the live UI and preserve configuration fingerprints.

## Review focus

- Requests whose database commit succeeds but whose response is lost.
- Stale views or two tabs making the same explicit selection.
- Completed/removed items shifting the saved position, and per-tab filter isolation.
- RSS creation failures or duplicate mount names must not hide or overwrite existing subscriptions.
- Summary polling failures must stop with an explicit recovery action and never fabricate successful text.

## Completion evidence

Record actual test totals and fixture acceptance results at completion. A passing unit suite alone does not prove the user workflow. Browser acceptance must show the final persisted results and their recovery paths. No source freshness, AI factual quality, or third-party uptime guarantees are inferred from fixture tests.

## Findings added during acceptance

- Real TokenHub generation failed before summary generation: the model-assisted extraction consumed 4096 output tokens, including 4077 reasoning tokens, with no usable article text. Increasing the temporary request budget to 8192 timed out. Clean, explicit HTML article containers now supply bounded original text directly; only whole-page fallback uses model-assisted extraction. A public article then completed extraction plus summarization with one request in 19.894 seconds under the unchanged 4096 limit. Truncated OpenAI responses remain rejected and have an actionable error.
- The subscription-created event and modal watcher competed for focus. The watcher now owns one-time return focus.
- Consumed reading snapshots no longer override later filter changes; fast typing before the input debounce is also preserved. Returning to a related briefing report expands its enclosing disclosure before restoring position.
- Custom RSS names now appear consistently in source selectors, digest groups, briefing reports and search results; categories follow their configured source route.

## Verified evidence

- Full Java suite: 501 tests, 0 failures/errors, 85 suites; `bootJar` succeeded. Node browser-behavior suite: 27 tests passed. `git diff --check` passed.
- Isolated app on port 14002 and fixture on 14003: invalid RSS input preserves the draft; duplicate creation preserves the original subscription; upstream 503 shows a retry deadline and automatic recovery saves eight articles.
- Browser: explicit summary failure followed by manual retry; bookmarking and completing an article while its summary runs; fresh read state and focus on the next visible article; compact briefing summary updates without page reload. Two fixture generation requests total (one failure, one success), with polling issuing no additional generation.
- Browser: saved summary-only keyword finds the bookmarked article; application restart preserves the summary, bookmark and read state. Browser Back restores filters and focused article after its fresh-state reload; later filter changes survive reload without replaying an old snapshot.
- At 390px, search and article pages have no horizontal overflow and article controls remain usable. Added subscription closes its dialog and focuses its new source heading.
- Lost-response retries, stale views, timeout release and related-report disclosure recovery are covered by focused Node/Java regressions.
- Local service updated on port 14001 using an immutable jar; AI, route and Feishu configuration fingerprints match their pre-acceptance values. The live article page successfully generated and saved its summary after one transient provider connection timeout; the existing 4096-token configuration was retained.

- Final article-body check found a short feed teaser hiding a substantial cached extract. The reader now chooses cached content when the cleaned source is under 200 code points and the extract is at least 200; complete saved source content retains priority and extracts remain escaped plain text. Three regression tests and the full suite passed.

- Body extraction now prefers specific inner article containers over broad page regions, removes generic navigation/button controls, and preserves paragraph boundaries. The public article cache contains 46 readable paragraphs within the same 4,000-code-point bound; only that generated extract was refreshed through FeedStore, with its successful summary preserved and no additional model request.
