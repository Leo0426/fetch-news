## Discovery Brief — saved-content reading (2026-10-05)

Problem: Saved article descriptions and searchable passages cannot be read inside the app; every list title opens the source website.

Target users: The same personal user prioritizing stable retrieval, reading efficiency, and optional local AI.

Current alternatives: Open a source tab or rely on a short summary; neither exposes the already saved passage directly.

Evidence:
- Fact: All reader list title links are external, and ReaderItem intentionally omits body content. FeedStore already retains descriptions and cached extracts.
- Assumption: A readable local detail page reduces interruptions and helps when the source is unavailable.
- Unknown: Actual time saved and how many subscribed sources store full text versus snippets.

MVP success metric: A saved passage can be opened from all five reading views without source/model requests; its provenance is clear, unsafe HTML is inert, explicit state changes persist, and returning preserves the original list query.

Decision: go. Implement a standalone reading page with secondary entry links and explicit completion; retain current external title behavior. See ../plans/2026-10-05-local-article-reading.md.

# Discovery Brief — shared local summary queue

Date: 2026-10-05

**Problem:** Manual summary requests omit already-saved descriptions, same-URL requests race through cache checks, and scheduled extraction bypasses coordination. Parallel jobs can overload the local model; a process restart leaves stale running labels.

**Target users:** The owner using an optional local Ollama instance alongside background news fetching.

**Current alternatives:** Generate one article at a time, wait for each task, and refresh/retry manually when a status gets stuck. These workarounds do not prevent background collisions or unnecessary article fetches.

**Evidence:** Direct inspection of `ReaderResource.summarize`, `AdminResource.summarizeItem`, `FetchScheduler.summarizeAsync`, and `ArticleSummarizer` confirms the missing input and coordination. Actual hardware throughput and model quality remain unmeasured.

**MVP success metric:** Concurrent requests for one saved URL produce one pipeline; different URLs never overlap model inference. A saved substantial description needs no article HTTP request. Queue/active/failure status remains truthful across reload and restart, and retry never happens merely because polling fails.

**Decision:** go, under the user's repeated authorization to select and implement the next requirement.

**Next step:** Implement the shared queue, persisted state, and polling in `docs/plans/2026-10-05-summary-queue.md`.

---

# Previous slice — Chinese history search

Date: 2026-10-04 (after the unread inbox slice)

**Problem:** A saved title such as 本地模型部署指南 cannot be found using 模型. The current search test masks the gap by surrounding its Chinese keyword with spaces. Search failures are rendered as no results, and description-only matches have no visible matching context.

**Target users:** The owner trying to retrieve an old read or unread article from a remembered Chinese phrase.

**Current alternatives:** Search an exact whole Chinese token, browse the unread inbox, or remember an English term. None reliably retrieves an already-read article by a Chinese short keyword.

**Evidence:** Code and a local SQLite/JDBC reproduction confirm the token boundary gap. The existing roadmap calls for Chinese retrieval. Actual user query frequency and human relevance judgments remain unknown; the fixed acceptance corpus is synthetic.

**MVP success metric:** All 30 frozen synthetic query targets appear in the top ten, preserving English word matching and literal punctuation semantics. Updated content, summaries, duplicate URLs, and restart retain correct results. Safe excerpts explain matches; storage failures cannot impersonate empty results.

**Decision:** go. The user again explicitly authorized selecting and implementing the next requirement.

**Next step:** Deliver the mixed word/literal search and visible matching context described in `docs/plans/2026-10-04-chinese-search.md`.

---

# Previous slice — persistent unread inbox

Date: 2026-10-04

**Problem:** The recent reader loads at most 500 items from a window of at most seven days before filtering unread items in the browser. Unread articles outside that window cannot be reached through the reader's unread filter.

**Target users:** The owner using fetch-news as a personal, self-hosted reader, including after several days away.

**Current alternatives:** Expand the recent window, remember an old article's search terms, or bookmark it before it leaves the window. These do not provide a complete unread queue.

**Evidence:**
- Fact: `ReaderResource.reader`, `FeedStore.loadRecentItems`, and the client-side `itemVisible` establish the window and row limit. The existing roadmap identifies this gap.
- Fact: Article URL, read state, and bookmarks already persist in SQLite; existing articles are not automatically deleted by age.
- Assumption: Recovering unread articles and working through a backlog is more valuable now than adding another source or a new visual theme.
- Unknown: Actual backlog size and frequency of use; no usage-frequency claim is made.

**MVP success metric:** Traverse 1,200 stored articles without duplicate or missing unread links, including old articles and concurrent new inserts. Scope totals, source/bookmark/keyword filters, restart persistence, and current-page-only read actions must remain correct. Browser verification covers mobile layout, keyboard continuity, and failed action feedback.

**Decision:** go. The user explicitly authorized selecting and implementing the next requirement.

**Next step:** Add `/reader/unread`, a database-filtered queue with 50-item keyset pages and existing reader actions. Keep the recent digest and the global URL identity. Full search-index redesign, multi-source ownership, import/export, and AI job orchestration remain separate work.
