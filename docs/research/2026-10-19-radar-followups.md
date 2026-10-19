# Radar follow-up delivery record

Implemented the approved order in commits 703418b, 2e7ec35, 523a404, f878ddd and aaeee0d, followed by the review fixes recorded in git.

- Profile UI: atomic prompt revisions, editing/copying profiles, source binding, thresholds, dedup switches and category quotas.
- Radar task panel: durable state/stage/errors, explicit resume or new-version retry, observed latency and provider token usage. Unknown/interrupted usage never appears complete.
- Model evaluation: persisted sample/pair snapshot, a fixed maximum of five calls, non-active platform execution, references kept outside inference prompts, preserved results and pause-on-restart.
- Saved report delivery: opt-in automatic discovery, explicit manual send, durable accepted-page cursor and uncertain-page confirmation. Never sends a report during an ordinary page load.
- HN source context: manual retrieval, article/story identity check, at most ten ranked top-level comments, plain text and attribution, independently saved cache and background reference links.

## Verification

The full Gradle test suite and bootJar passed with 344 tests and zero failures/errors. JavaScript syntax and git whitespace checks passed. A separate final review found four important issues, each fixed with regression coverage: output filename collisions, expected-answer leakage, ignored evaluation snapshots and incomplete token coverage after interruption. Browser inspection found unregistered new static assets; explicit HTTP mapping tests now cover both assets.

An isolated application/database and local fake OpenAI endpoint were used for browser verification: Profile switch/save, fixed evaluation results, saved report generation, task metrics and community validation feedback. Reader/evaluation layout checked at 390px with no document horizontal overflow. Desktop task panel screenshot: `build/radar-followup-preview/tasks.png`.

No real inference endpoint or Feishu webhook was called, and existing user runtime configuration was not changed. Actual model quality/cost and group-card rendering still require user-initiated validation. Existing uncommitted briefing changes remain outside these commits. Work remains on the current feature branch; no merge, push or publication was performed.
