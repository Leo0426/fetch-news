# A shared, observable local summary queue

Manual reader/admin actions currently discard saved descriptions, while scheduled enrichment runs outside summary coordination. Concurrent requests can generate the same summary more than once. Virtual-thread submission does not bound model concurrency; unfinished status survives restart without recovery.

## Delivery contract

- All reader, admin, batch, and scheduled requests enqueue saved article URLs through `ArticleSummarizer`. Unknown URLs are rejected. A nonempty cached summary wins; queued/running URLs are not duplicated. Only explicit manual retry requeues a failed task.
- Queue state uses the existing `ai_summary_runs` table. One dedicated worker processes a full article pipeline at a time. Queued work survives restart and remains paused while AI is disabled. Work interrupted while running is marked failed with an explicit retry message on startup; an already-saved summary reconciles to success. There is no automatic retry loop for generation failures.
- Load canonical title and description from `FeedStore`. Reuse substantial cleaned descriptions and cached extracts. Short/missing input uses the existing model-assisted extraction once inside the same pipeline; all article HTTP uses the shared `FetchClient`. Preserve the current extraction prompt until quality can be evaluated separately.
- Store the model used by the actual summary response. Input limits preserve Unicode code points. Empty responses, missing article text, exceptions without messages, and shutdown interruption must not become success.
- Reader submission returns quickly with JSON progress; GET status only observes. Cards restore polling after reload, distinguish queued/running/success/failure, and never repeat a POST merely because status delivery failed. Admin HTMX fragments and batch feedback follow the same state semantics.
- A closed worker stops admitting work and does not start another model request. The model and database remain optional in null-store unit configurations.

## Verification

Use temporary SQLite databases, a fake article fetch boundary and local fake Ollama service. Verify duplicate submissions, two URLs with a blocked first task, saved-body reuse without networking, short-body extraction exactly once, explicit retry, empty/error responses, model changes while queued, AI pause/resume, restart state recovery, status reads without inference, and shutdown. Browser checks use a dedicated fake model and isolated fixtures; no real Ollama or live article sources are called.

This is a single-process queue. Leases for multiple application instances, model/content-version cache keys, automatic retry policies, and model-quality evaluation remain separate work. A model request interrupted after upstream acceptance can be recomputed after explicit retry; exactly-once inference across crashes is not promised.

## Implementation and checks

Implemented in `ArticleSummarizer`, `ArticleExtractor`, `OllamaClient`, and `FeedStore`, with reader JSON and admin HTMX adapters. The existing run table now has a queue index; no new job table or external queue is needed. Queue timestamps use fixed nanosecond precision for FIFO ordering. A lifecycle lock protects admission and shutdown, while a single worker owns generation. Completed status rechecks the summary cache to cover completion between reads.

Automated verification on 2026-10-05: `./gradlew test bootJar --console=plain` passed **250 tests in 36 suites**, with no failures, errors, or skips; the executable jar was built. New tests use temporary databases and fake model/article boundaries. Coverage includes concurrent claims, duplicate submissions, canonical saved content, explicit retry, empty outputs, exceptions without messages, Unicode truncation, model changes during requests, pause/resume, restart recovery, shutdown during model and article requests, scheduler enqueueing, controller responses, URL escaping, and the progress-read race.

Browser verification uses a separate database and fake local Ollama at `build/ai-preview`. The first eight scenarios passed: serial processing across two articles, reload recovery, response loss after accepted submission, read-only recovery after network failure, admin polling after reopening, batch deduplication, disabled-AI pause/resume, and explicit retry with escaped model output. Reader and admin were checked at 320, 390, 768, and 1440 pixels. The fake model observed at most one active generation; substantial saved descriptions required no article requests. These checks establish queue and interface behavior, not real-model quality or production latency.

## Remaining work

The next useful slice is a fixed local article set for extraction/summary quality and resource measurements, followed by cache versioning where evidence shows stale results. The current change deliberately keeps model-assisted extraction, a fixed single worker, and explicit retries. Configurable concurrency, distributed leases, automatic retries, and model/content/prompt cache keys remain outside this delivery.

Final admin browser checks also passed: a lost single-item POST response only offers a GET status check; a lost batch response only offers a GET list refresh; a successful batch refreshes the same source/page and starts row polling automatically. The separate real-HTMX harness also covers timeout, stopped polling after query failure, special URLs, and late batch responses after navigation. Final verification remained **250 passing Java tests**; **11 application-browser scenarios** passed, with layout checks across four viewport widths. Preview fixtures include three fresh articles for manually trying the completed interaction.
