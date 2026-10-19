# Radar Follow-ups Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement the tasks in order.

**Goal:** Make radar configurable, observable, evaluable, deliverable and source-backed.
**Architecture:** Keep immutable run snapshots and saved reports. Add separate admin controllers and small services; persist jobs, metrics and delivery attempts only through FeedStore. Existing defaults remain opt-in.
**Tech Stack:** Java 25, Spring Boot 4, SQLite, Thymeleaf and HTMX.
**Spec:** User-approved sequence: Profile UI → AI task panel → model evaluation → Feishu radar delivery → community context.

## Global Constraints

- Preserve all existing uncommitted briefing work.
- No real paid inference or external messages during development verification.
- Profile changes affect new runs; saved runs and reports remain immutable.
- All article/community HTTP goes through FetchClient; database access through FeedStore.
- Defaults keep evaluation, enrichment and automatic delivery inactive.

## Review Focus

- Concurrent Profile saves cannot leave a partially readable manifest.
- Unknown token usage must remain unknown rather than become a misleading zero.
- Retries must not silently repeat successful external delivery pages.
- Evaluation must pin its platform configuration and bound model calls.
- Community text is untrusted data; preserve source links and do not fabricate context.

### Task 1: Profile administration
**Files:** ProfileStore.java, RadarAdminResource.java, RadarAdminRenderer.java, templates/index/radar-settings.html, static/admin.js, templates/index/index.html; ProfileStoreTest and RadarAdminTest.
**Interfaces:** ProfileStore.save(Profile); GET/POST /index/radar; POST /index/radar/profiles; POST /index/radar/config.
- [x] Write tests for atomic prompt roundtrip, invalid block IDs and form persistence/validation.
- [x] Run focused tests and observe RED.
- [x] Implement versioned prompt files with atomic manifest, Profile editing/clone, source bindings, thresholds, quotas and scheduling controls.
- [x] Run focused tests, expect PASS; commit.

### Task 2: Task visibility and usage
**Files:** OllamaClient.java, GenerationMetrics.java, RadarWorker.java, FeedStore.java, RadarService.java, RadarAdminResource.java, templates/index/radar-tasks.html.
**Interfaces:** FeedStore.listRadarRuns(int); generation observer completion hook; durable per-run usage; POST /index/radar/tasks/{id}/retry.
- [x] Test usage parsing across three protocols, absent usage, durable task listing and explicit retry.
- [x] Observe RED, implement task panel with stage/errors/resume/new-version retry and observed latency/tokens.
- [x] Run focused tests, expect PASS; commit.

### Task 3: Reproducible model evaluation
**Files:** app/radar/eval/*, FeedStore.java, RadarAdminResource.java, templates/index/radar-evaluation.html, sample fixtures.
**Interfaces:** EvaluationService.submit(platformId,requestKey); durable bounded background evaluations; GET /index/radar/evaluation.
- [x] Test fixed samples, model snapshot, restart recovery and bounded calls; observe RED.
- [x] Implement manual evaluation of scoring, summary and same-event decisions using stored platform config without changing active platform; preserve raw verdicts and metrics with reference expectations.
- [x] Run fake-provider tests, expect PASS; commit. Live paid tests remain user initiated.

### Task 4: Durable Feishu report delivery
**Files:** app/feishu/RadarDelivery*.java, FeedStore.java, admin template/controller, AppConfig.java.
**Interfaces:** Saved-report-only delivery; durable page receipts; explicit manual send/retry and separately opt-in automatic delivery.
- [x] Test multiple pages, success deduplication, partial failure/retry and paused interrupted attempts; observe RED.
- [x] Implement escaped cards, asynchronous sending and durable ledger, using existing Feishu config.
- [x] Run local webhook tests, expect PASS; commit.

### Task 5: Community source context
**Files:** app/radar/CommunityContext.java and service, FeedStore.java, admin and reader templates.
**Interfaces:** Explicit cached Hacker News discussion lookup, FetchClient-only retrieval, provenance URLs; optional manual lookup for saved stories.
- [x] Test HN IDs, bounded comments, sanitization and source attribution; observe RED.
- [x] Implement source-backed context with no fabricated model claims; keep background enrichment manual by default.
- [x] Run local-fixture tests, expect PASS; commit.

### Completion
- [x] Run complete test suite and bootJar, JavaScript syntax checks and diff whitespace checks.
- [x] Inspect admin UI at desktop/mobile dimensions with isolated preview fixtures.
- [x] Fresh final code review; fix important findings with regression tests.
- [x] Document usage and limitations, preserve user WIP and report exact verification.
