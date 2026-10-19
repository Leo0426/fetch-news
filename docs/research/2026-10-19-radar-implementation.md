# Radar implementation record

Branch: `feat/horizon-core`; base: `42bb461`.

Ruling: Work in feat/horizon-core in the existing checkout, preserving all existing changes — avoids transferring or committing user WIP; cost: working directory shared with user.
Pre-flight: Task 1 config/Profile -> Tasks 2/4/7/9: snapshot and validate together; consistent.
Pre-flight: Task 2 snapshots -> Tasks 4–8: immutable input, settings, profiles, model identity; add explicit model identity to RunRequest, missing from plan signature detail.
Pre-flight: Task 3 gate -> Tasks 4/6/7: shared Ollama client; extraction also consumes same run budget through a scoped observer.
Pre-flight: Tasks 4/5/6 stages -> Task 7: checkpoint each candidate and completed stage; avoid recomputing completed model calls.
Pre-flight: Task 7 service -> Tasks 8/9: service owns idempotent submission, persisted versioning; consistent.
Tasks: 1 configuration, 2 persistence, 3 Ollama, 4 analysis, 5 dedup, 6 selection, 7 worker, 8 reader, 9 scheduler/QA.
Task 1: complete — missing-class RED; ProfileStoreTest/RadarConfigStoreTest GREEN (4 tests); baseline Gradle test successful (up-to-date).
Task 2: complete — RED missing types/methods, GREEN store suite and FeedFetcherPersistenceTest. Inputs snapshotted and dual source/restart/201 candidate behavior verified.
Task 3: complete — missing generateJson RED, full ai suite GREEN. Scoped generation guard also covers extraction budget and model identity.
Task 4: complete — missing analysis/router RED; targeted tests GREEN, fixed binding precedence, invalid scores/fields and cache invalidation covered.
Task 5: complete — missing deduplicator RED; grouping/invalid ID/Profile boundary GREEN.
Task 6: complete — missing selector/generator/renderer RED, radar suite GREEN. Cross-Profile quotas and unsafe Markdown verified.
Task 7: complete — missing service/worker RED; worker/service tests and full Gradle suite GREEN.
Ruling: Checkpoint score/routing per article, enrichment per story, dedup per completed stage — stage-level recovery is simpler, interrupted dedup may repeat calls; persisted budget includes attempted calls.
Ruling: Isolate radar paths in build.gradle during Task 7 rather than Task 9 — required before Spring beans can be safely tested.
Task 8: complete — missing resource RED; template failed because Context does not support context-relative links, corrected to existing explicit URL encoding pattern; targeted reader suite pending: second run exposed missing explicit UTF-8 charset in standalone MVC, fixed via response content type; verify with Task 9.
Ruling: New RadarTemplateRenderer delegates rendering, leaving dirty AdminTemplateRenderer untouched. Briefing template commit stages only our navigation link; pre-existing WIP remains unstaged.
Task 9: code complete — scheduler RED then GREEN (idempotency/catch-up/DST); scenario pipeline and complete suite 322/322, bootJar successful.
Quality gate pending: real Ollama unavailable at 127.0.0.1:11434; no 40-article human-labelled quality evaluation or real model latency claims. Default remains disabled.
Task 8: final verification GREEN, UTF-8 and template link fixes confirmed through MockMvc and isolated browser.
Final review: independent gpt-6-astra review of whole branch plus working tree, all concrete Important findings reproduced RED and fixed GREEN.
Final: fixed strict model JSON — rejects trailing tokens and duplicate fields; analysis invalid response regression RED→GREEN.
Final: fixed quota default bucket collision — explicit other group regression RED→GREEN.
Final: fixed known different publication dates/versions and symmetric dedup metadata — two grouping regressions RED→GREEN.
Final: fixed last-call model configuration check before publication — pause/resume regression RED→GREEN.
Final: fixed SQL exceptions misclassified as article failures — persistent-store failure regression RED→GREEN.
Final: fixed cross-date idempotency key reuse — key conflict regression RED→GREEN.
Final: fixed partial-report missing below-threshold decisions and inaccurate failures — decisions/count regression RED→GREEN.
Final: fixed invalid neighboring block dropping valid blocks — partial blocks regression RED→GREEN.
Final: fixed crash resetting runtime budget — persisted attempt-start/recovery regression RED→GREEN.
Final: regraded configured block order from Minor to Important because default narrative order is user-visible; preserved block order and original export headlines, regression RED→GREEN.
Ruling: Crash recovery conservatively charges an unfinished attempt through recovery (including downtime) — prevents resetting budget on repeated crashes; cost: long downtime may exhaust remaining budget and require a new run.
Ruling: README/CONTEXT documentation additions staged from HEAD plus only our additions — preserves pre-existing user WIP outside commits; cost: WIP still needs its owner's review.
Ruling: Real-model quality gate remains pending rather than inventing labels/results — local Ollama unavailable; cost: automatic daily use should wait for model calibration.
Browser: isolated fake HTTP model and SQLite, generate/reload/version behavior verified; 320/390/768/1440 widths each no horizontal overflow.

Verification: `./gradlew test bootJar --console=plain` succeeds, 322 tests in 59 suites, zero failures/errors; `node --check src/main/resources/static/radar.js` and `git diff --check` succeed. Existing Java native-access and test-agent warnings remain non-failing.

Browser QA used only isolated fixture data and fake Ollama on ports 18090/18091. Two versions generated successfully, prior version remained readable while the next was queued, final block order was summary → importance, and four viewport widths had no overflow. Screenshot capture worked once during mobile inspection but subsequent browser capture calls timed out; no durable screenshot artifact is claimed. Viewport restored; preview processes stopped after QA.
