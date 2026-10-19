package app;

import app.ai.AiConfigStore;
import app.ai.ArticleSummarizer;
import app.ai.SummaryProgress;
import app.reader.ArticleNavigation;
import app.reader.ArticlePageView;
import app.reader.BriefingSection;
import app.reader.ReaderGroup;
import app.reader.ReaderItem;
import app.reader.SearchPageView;
import app.reader.SearchResultView;
import app.reader.UnreadPageView;
import app.store.BookmarkedItem;
import app.store.FeedStore;
import app.store.SearchHit;
import app.store.StoredArticle;
import app.store.StoredItem;
import app.store.SummaryStatus;
import app.store.UnreadPage;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Serves the reader page — a daily digest of all recently fetched items grouped by category.
 */
@Controller
public class ReaderResource {
    private static final Logger log = LoggerFactory.getLogger(ReaderResource.class);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日");

    private final FeedStore feedStore;
    private final AppRuntime runtime;
    private final AdminTemplateRenderer templates;
    private final ArticleSummarizer articleSummarizer;
    private final AiConfigStore aiConfigStore;

    public ReaderResource(FeedStore feedStore, AppRuntime runtime, AdminTemplateRenderer templates,
                          ArticleSummarizer articleSummarizer, AiConfigStore aiConfigStore) {
        this.feedStore = feedStore;
        this.runtime = runtime;
        this.templates = templates;
        this.articleSummarizer = articleSummarizer;
        this.aiConfigStore = aiConfigStore;
    }

    /**
     * Reader page — shows items fetched within the last {@code hours} hours, grouped by category.
     *
     * @param hours time window in hours (default 24, max 168)
     */
    @GetMapping(value = "/reader", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String reader(@RequestParam(defaultValue = "24") int hours) {
        int safeHours = Math.min(Math.max(hours, 1), 168);
        List<StoredItem> stored = List.of();
        if (feedStore != null) {
            try {
                stored = feedStore.loadRecentItems(Duration.ofHours(safeHours));
            } catch (Exception e) {
                log.warn("failed to load recent items: {}", e.getMessage());
            }
        }

        // Batch-load AI summaries for all items in one query
        java.util.Map<String, String> summaries = java.util.Map.of();
        java.util.Map<String, SummaryStatus> summaryStatuses = java.util.Map.of();
        if (feedStore != null && !stored.isEmpty()) {
            try {
                List<String> links = stored.stream()
                        .filter(s -> s.link() != null)
                        .map(app.store.StoredItem::link)
                        .toList();
                summaries = feedStore.loadSummaries(links);
                summaryStatuses = feedStore.loadSummaryStatuses(links);
            } catch (Exception e) {
                log.warn("failed to load summaries: {}", e.getMessage());
            }
        }

        Map<String, String> categories = runtime.routeRegistry().categories();
        // Group by routePath (concrete source), preserving DB insertion order (newest first)
        LinkedHashMap<String, List<ReaderItem>> byRoute = new LinkedHashMap<>();
        final java.util.Map<String, String> finalSummaries = summaries;
        final java.util.Map<String, SummaryStatus> finalSummaryStatuses = summaryStatuses;

        for (StoredItem s : stored) {
            if (s.title() == null || s.title().isBlank()) continue;
            if (s.link()  == null || s.link().isBlank())  continue;
            String summary = finalSummaries.get(s.link());
            SummaryStatus summaryStatus = finalSummaryStatuses.get(s.link());
            byRoute.computeIfAbsent(s.routePath(), k -> new ArrayList<>())
                    .add(new ReaderItem(s.routePath(), s.title(), s.link(),
                            s.pubDate(), s.firstSeenAt(), summary, summaryStatus));
        }

        // Build groups; sort by the firstSeenAt of the first (newest) item in each group
        List<ReaderGroup> groups = byRoute.entrySet().stream()
                .map(e -> {
                    List<ReaderItem> items = e.getValue();
                    ReaderItem first = items.getFirst();
                    String tag = matchCategory(e.getKey(), categories);
                    return new ReaderGroup(first.groupName(), tag, items);
                })
                .sorted(java.util.Comparator.comparing(
                        g -> g.items().getFirst().firstSeenAt(),
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();

        String date = LocalDate.now().format(DATE_FMT);
        boolean aiEnabled = aiConfigStore != null && aiConfigStore.get().enabled();
        List<String> bookmarkedLinks = List.of();
        List<String> readLinks = List.of();
        if (feedStore != null) {
            try { bookmarkedLinks = feedStore.loadBookmarkLinks(); }
            catch (Exception e) { log.warn("failed to load bookmark links: {}", e.getMessage()); }
            try { readLinks = feedStore.loadReadLinks(); }
            catch (Exception e) { log.warn("failed to load read links: {}", e.getMessage()); }
        }
        return templates.readerPage(groups, date, stored.size(), safeHours, aiEnabled, bookmarkedLinks, readLinks);
    }

    @GetMapping(value = "/reader/article", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> article(
            @RequestParam(defaultValue = "") String link,
            @RequestParam(defaultValue = "") String returnTo) {
        if (ArticleNavigation.originalUrl(link) == null) {
            return articleError(400, "文章链接无效，请从阅读列表重新打开。", returnTo, null);
        }
        if (feedStore == null) return articleError(503, "文章库暂时不可用，请稍后重试。", returnTo, link);
        try {
            StoredArticle article = feedStore.loadArticle(link);
            if (article == null) return articleError(404, "这篇文章没有保存在本地，可以打开原文继续阅读。", returnTo, link);
            return ResponseEntity.ok().header("Cache-Control", "no-store")
                    .body(templates.articlePage(ArticlePageView.from(article, returnTo)));
        } catch (SQLException e) {
            log.warn("failed to load article: {}", e.toString());
            return articleError(500, "已保存内容暂时加载失败，请稍后重试。", returnTo, link);
        }
    }

    private ResponseEntity<String> articleError(int status, String message, String returnTo, String link) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(templates.articlePage(ArticlePageView.error(message, returnTo, link)));
    }

    @PutMapping(value = "/reader/article/bookmark",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> setArticleBookmark(@RequestParam String link, @RequestParam boolean bookmarked) {
        if (ArticleNavigation.originalUrl(link) == null) return ResponseEntity.badRequest().body("文章链接无效。");
        if (feedStore == null) return ResponseEntity.status(503).body("文章库暂时不可用。");
        try {
            StoredArticle article = feedStore.loadArticle(link);
            if (article == null) return ResponseEntity.status(404).body("文章没有保存在本地。");
            if (bookmarked) {
                feedStore.saveBookmark(article.item().link(), article.item().title(), article.routePath());
            } else {
                feedStore.removeBookmark(article.item().link());
            }
            return ResponseEntity.ok(bookmarked ? "bookmarked" : "removed");
        } catch (SQLException e) {
            log.warn("failed to set article bookmark: {}", e.toString());
            return ResponseEntity.status(500).body("收藏状态暂时无法确认，请重试。");
        }
    }

    @GetMapping(value = "/reader/unread", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> unread(
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "false") boolean bookmarked,
            @RequestParam(required = false) Long before) {
        source = source.strip();
        q = q.strip();
        if ((before != null && before <= 0) || source.length() > 2048 || q.length() > 200) {
            return unreadError(400, source, q, bookmarked,
                    "筛选或翻页参数无效。关键词最多 200 个字符，请重新选择条件。");
        }
        if (feedStore == null) {
            return unreadError(503, source, q, bookmarked, "文章库暂时不可用，请稍后重试。");
        }
        UnreadPageView page;
        try {
            UnreadPage stored = feedStore.loadUnreadPage(source, q, bookmarked, before, 50);
            List<String> links = stored.items().stream().map(StoredItem::link).toList();
            Map<String, String> summaries = feedStore.loadSummaries(links);
            Map<String, SummaryStatus> statuses = feedStore.loadSummaryStatuses(links);
            List<ReaderItem> items = stored.items().stream()
                    .map(item -> new ReaderItem(item.routePath(), item.title(), item.link(),
                            item.pubDate(), item.firstSeenAt(), summaries.get(item.link()), statuses.get(item.link())))
                    .toList();
            page = new UnreadPageView(items, stored.bookmarkedLinks(), stored.sources(), source, q,
                    bookmarked, stored.totalCount(), stored.nextCursor() == null ? null
                            : unreadUrl(source, q, bookmarked, stored.nextCursor()),
                    unreadUrl(source, q, bookmarked, null), before != null,
                    aiConfigStore != null && aiConfigStore.get().enabled(), null);
        } catch (SQLException e) {
            log.warn("failed to load unread inbox: {}", e.getMessage());
            return unreadError(500, source, q, bookmarked, "待读内容暂时加载失败，请重试。");
        }
        return ResponseEntity.ok(templates.unreadPage(page));
    }

    private ResponseEntity<String> unreadError(int status, String source, String query,
                                               boolean bookmarked, String message) {
        UnreadPageView page = new UnreadPageView(List.of(), List.of(), List.of(), source, query,
                bookmarked, 0, null, status == 400 ? "/reader/unread" : unreadUrl(source, query, bookmarked, null),
                false, false, message);
        return ResponseEntity.status(status).body(templates.unreadPage(page));
    }

    private static String unreadUrl(String source, String query, boolean bookmarked, Long before) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath("/reader/unread");
        if (!source.isEmpty()) url.queryParam("source", "{source}");
        if (!query.isEmpty()) url.queryParam("q", "{query}");
        if (bookmarked) url.queryParam("bookmarked", true);
        if (before != null) url.queryParam("before", before);
        return url.encode().buildAndExpand(Map.of("source", source, "query", query)).toUriString();
    }

    @GetMapping(value = "/reader/search", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> search(@RequestParam(defaultValue = "") String q) {
        String query = q.replaceAll("(?U)^\\s+|\\s+$", "");
        if (query.isEmpty()) {
            return ResponseEntity.ok(templates.searchPage(new SearchPageView(query, List.of(), false, null)));
        }
        int length = query.codePointCount(0, query.length());
        if (length < 2 || length > 200) {
            return searchError(400, query, "请输入 2–200 个字符，多个关键词用空格分隔。");
        }
        if (feedStore == null) {
            return searchError(503, query, "文章库暂时不可用，请稍后重试。");
        }
        try {
            List<SearchHit> hits = feedStore.searchHits(query, 101);
            List<SearchResultView> items = hits.stream().limit(100).map(hit -> {
                StoredItem item = hit.item();
                ReaderItem article = new ReaderItem(item.routePath(), item.title(), item.link(),
                        item.pubDate(), item.firstSeenAt(), hit.summary(), null);
                return SearchResultView.from(article, hit.description(), query);
            }).toList();
            return ResponseEntity.ok(templates.searchPage(new SearchPageView(query, items, hits.size() > 100, null)));
        } catch (SQLException e) {
            log.warn("history search failed: {}", e.getMessage());
            return searchError(500, query, "搜索暂时失败，请稍后重试；已保存的文章仍保留在文章库中。");
        }
    }

    private ResponseEntity<String> searchError(int status, String query, String message) {
        return ResponseEntity.status(status)
                .body(templates.searchPage(new SearchPageView(query, List.of(), false, message)));
    }

    @PostMapping(value = "/reader/summarize",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<SummaryProgress> summarize(
            @RequestParam String link,
            @RequestParam(required = false, defaultValue = "") String title) {
        if (aiConfigStore == null || !aiConfigStore.get().enabled()) {
            return summaryError(400, "AI 功能未启用，请先在后台配置 Ollama。");
        }
        return summaryResponse(link, true);
    }

    @GetMapping(value = "/reader/summary-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<SummaryProgress> summaryStatus(@RequestParam String link) {
        return summaryResponse(link, false);
    }

    private ResponseEntity<SummaryProgress> summaryResponse(String link, boolean submit) {
        if (link == null || link.isBlank()) return summaryError(400, "缺少文章链接。");
        if (feedStore == null || articleSummarizer == null) return summaryError(503, "文章库暂时不可用。");
        try {
            SummaryProgress progress = submit ? articleSummarizer.request(link) : articleSummarizer.progress(link);
            return ResponseEntity.status(submit && progress.pending() ? 202 : 200).body(progress);
        } catch (NoSuchElementException e) {
            return summaryError(404, "文章尚未保存，无法生成摘要。");
        } catch (IllegalStateException e) {
            return summaryError(503, "摘要服务暂时不可用，请稍后重试。");
        } catch (SQLException e) {
            log.warn("summary request failed: {}", e.toString());
            return summaryError(500, "摘要状态暂时不可用，请稍后重试。");
        }
    }

    private ResponseEntity<SummaryProgress> summaryError(int status, String message) {
        return ResponseEntity.status(status).body(new SummaryProgress("error", null, message));
    }

    /**
     * Bookmarks page — lists all saved articles.
     */
    @GetMapping(value = "/reader/bookmarks", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String bookmarks() {
        List<BookmarkedItem> items = List.of();
        if (feedStore != null) {
            try { items = feedStore.loadBookmarks(); }
            catch (Exception e) { log.warn("failed to load bookmarks: {}", e.getMessage()); }
        }
        return templates.bookmarksPage(items);
    }

    /**
     * Toggles a bookmark: adds if absent, removes if present.
     * Returns "bookmarked" or "removed" as plain text.
     */
    @PostMapping(
            value = "/reader/bookmark",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> toggleBookmark(
            @RequestParam String link,
            @RequestParam(required = false, defaultValue = "") String title,
            @RequestParam(required = false, defaultValue = "") String routePath) {
        if (feedStore == null) return ResponseEntity.status(503).body("error");
        try {
            if (feedStore.isBookmarked(link)) {
                feedStore.removeBookmark(link);
                return ResponseEntity.ok("removed");
            }
            feedStore.saveBookmark(link,
                    title.isBlank() ? null : title,
                    routePath.isBlank() ? null : routePath);
            return ResponseEntity.ok("bookmarked");
        } catch (Exception e) {
            log.warn("bookmark toggle failed for {}: {}", link, e.getMessage());
            return ResponseEntity.status(500).body("error");
        }
    }

    /**
     * Removes a bookmark (used by the bookmarks page HTMX delete button).
     * Returns empty HTML so HTMX removes the card via outerHTML swap.
     */
    @DeleteMapping(
            value = "/reader/bookmark",
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> removeBookmark(@RequestParam String link) {
        if (feedStore == null) return ResponseEntity.status(503).body("");
        try {
            feedStore.removeBookmark(link);
            return ResponseEntity.ok("");
        } catch (Exception e) {
            log.warn("bookmark remove failed for {}: {}", link, e.getMessage());
            return ResponseEntity.status(500).body("取消收藏失败，请稍后重试");
        }
    }

    /**
     * Toggles read state for a single item. Returns "read" or "unread".
     */
    @PostMapping(
            value = "/reader/read",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> toggleRead(@RequestParam String link) {
        if (feedStore == null) return ResponseEntity.status(503).body("error");
        try {
            if (feedStore.isRead(link)) {
                feedStore.unmarkRead(link);
                return ResponseEntity.ok("unread");
            }
            feedStore.markRead(link);
            return ResponseEntity.ok("read");
        } catch (Exception e) {
            log.warn("read toggle failed for {}: {}", link, e.getMessage());
            return ResponseEntity.status(500).body("error");
        }
    }

    /**
     * Marks all supplied links as read in a single batch.
     */
    @PostMapping(
            value = "/reader/read-all",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> markAllRead(@RequestParam List<String> links) {
        if (feedStore == null) return ResponseEntity.status(503).body("error");
        try {
            feedStore.markAllRead(links);
            return ResponseEntity.ok("ok");
        } catch (Exception e) {
            log.warn("mark-all-read failed: {}", e.getMessage());
            return ResponseEntity.status(500).body("error");
        }
    }

    /**
     * Daily briefing page — items for a specific calendar date, grouped by category tag.
     */
    @GetMapping(value = "/reader/briefing", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String briefing(@RequestParam(required = false) String date) {
        LocalDate targetDate;
        try {
            targetDate = (date != null && !date.isBlank())
                    ? LocalDate.parse(date)
                    : LocalDate.now();
        } catch (Exception e) {
            targetDate = LocalDate.now();
        }

        List<StoredItem> stored = List.of();
        if (feedStore != null) {
            try {
                stored = feedStore.loadItemsForDate(targetDate);
            } catch (Exception e) {
                log.warn("failed to load items for briefing date {}: {}", targetDate, e.getMessage());
            }
        }

        java.util.Map<String, String> summaries = java.util.Map.of();
        java.util.Map<String, SummaryStatus> summaryStatuses = java.util.Map.of();
        if (feedStore != null && !stored.isEmpty()) {
            try {
                List<String> links = stored.stream()
                        .filter(s -> s.link() != null)
                        .map(StoredItem::link)
                        .toList();
                summaries = feedStore.loadSummaries(links);
                summaryStatuses = feedStore.loadSummaryStatuses(links);
            } catch (Exception e) {
                log.warn("failed to load summaries for briefing: {}", e.getMessage());
            }
        }

        Map<String, String> categories = runtime.routeRegistry().categories();
        final java.util.Map<String, String> finalSummaries = summaries;

        LinkedHashMap<String, List<ReaderItem>> byCategory = new LinkedHashMap<>();
        final java.util.Map<String, SummaryStatus> finalBriefingSummaryStatuses = summaryStatuses;
        for (StoredItem s : stored) {
            if (s.title() == null || s.title().isBlank()) continue;
            if (s.link()  == null || s.link().isBlank())  continue;
            String summary = finalSummaries.get(s.link());
            SummaryStatus summaryStatus = finalBriefingSummaryStatuses.get(s.link());
            String cat = matchCategory(s.routePath(), categories);
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>())
                    .add(new ReaderItem(s.routePath(), s.title(), s.link(),
                            s.pubDate(), s.firstSeenAt(), summary, summaryStatus));
        }

        List<BriefingSection> sections = byCategory.entrySet().stream()
                .map(e -> BriefingSection.from(e.getKey(), e.getValue()))
                .sorted(java.util.Comparator.comparingInt(BriefingSection::totalStoryCount).reversed())
                .toList();

        int articleCount = sections.stream().mapToInt(BriefingSection::totalCount).sum();
        return templates.briefingPage(sections, targetDate, articleCount);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String matchCategory(String routePath, Map<String, String> categories) {
        if (routePath == null) return "其他";
        String direct = categories.get(routePath);
        if (direct != null) return direct;
        for (var entry : categories.entrySet()) {
            if (pathMatchesPattern(routePath, entry.getKey())) return entry.getValue();
        }
        return "其他";
    }

    private static boolean pathMatchesPattern(String path, String pattern) {
        String[] pp = path.split("/");
        String[] pt = pattern.split("/");
        if (pp.length != pt.length) return false;
        for (int i = 0; i < pt.length; i++) {
            if (!pt[i].startsWith(":") && !pt[i].equals(pp[i])) return false;
        }
        return true;
    }
}
