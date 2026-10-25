package app.reader;

import app.AppRuntime;
import app.ai.AiConfigStore;
import app.ai.ArticleSummarizer;
import app.ai.SummaryProgress;
import app.auth.CurrentUser;
import app.store.BookmarkedItem;
import app.store.FeedStore;
import app.store.ItemPick;
import app.store.PickFeedback;
import app.store.PickKind;
import app.store.SearchHit;
import app.store.StoredArticle;
import app.store.StoredItem;
import app.store.UnreadPage;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final ReaderTemplateRenderer templates;
    private final ArticleSummarizer articleSummarizer;
    private final AiConfigStore aiConfigStore;
    private final ReaderItems readerItems;

    public ReaderResource(FeedStore feedStore, AppRuntime runtime, ReaderTemplateRenderer templates,
                          ArticleSummarizer articleSummarizer, AiConfigStore aiConfigStore) {
        this.feedStore = feedStore;
        this.runtime = runtime;
        this.templates = templates;
        this.articleSummarizer = articleSummarizer;
        this.aiConfigStore = aiConfigStore;
        this.readerItems = feedStore == null ? null : new ReaderItems(feedStore);
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
                stored = feedStore.items().loadRecentItems(Duration.ofHours(safeHours));
            } catch (Exception e) {
                log.warn("failed to load recent items: {}", e.getMessage());
            }
        }

        ReaderItems.Page cards = loadCards(stored, "reader");
        ReaderPicks picks = cards.picks();
        List<ReaderGroup> groups = ReaderGroup.bySource(cards.items(),
                path -> runtime.routeConfigStore().get(path).alias(), runtime::categoryOf);

        String date = LocalDate.now().format(DATE_FMT);
        boolean aiEnabled = aiConfigStore != null && aiConfigStore.get().enabled();
        List<String> bookmarkedLinks = List.of();
        List<String> readLinks = List.of();
        if (feedStore != null) {
            try { bookmarkedLinks = feedStore.reading().loadBookmarkLinks(CurrentUser.id()); }
            catch (Exception e) { log.warn("failed to load bookmark links: {}", e.getMessage()); }
            try { readLinks = feedStore.reading().loadReadLinks(CurrentUser.id()); }
            catch (Exception e) { log.warn("failed to load read links: {}", e.getMessage()); }
        }
        return templates.readerPage(groups, date, stored.size(), safeHours, aiEnabled, bookmarkedLinks, readLinks, picks);
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
            StoredArticle article = feedStore.loadArticle(CurrentUser.id(), link);
            if (article == null) return articleError(404, "这篇文章没有保存在本地，可以打开原文继续阅读。", returnTo, link);
            boolean aiEnabled = aiConfigStore != null && aiConfigStore.get().enabled();
            SummaryProgress progress = articleSummaryProgress(article);
            return ResponseEntity.ok().header("Cache-Control", "no-store")
                    .body(templates.articlePage(ArticlePageView.from(article, returnTo, aiEnabled, progress)));
        } catch (SQLException e) {
            log.warn("failed to load article: {}", e.toString());
            return articleError(500, "已保存内容暂时加载失败，请稍后重试。", returnTo, link);
        }
    }

    private SummaryProgress articleSummaryProgress(StoredArticle article) {
        if (article.summary() != null && !article.summary().isBlank())
            return new SummaryProgress("success", article.summary(), null);
        if (articleSummarizer == null) return new SummaryProgress("none", null, null);
        try {
            return articleSummarizer.progress(article.item().link());
        } catch (SQLException | IllegalStateException | NoSuchElementException e) {
            log.warn("failed to read article summary status: {}", e.getClass().getSimpleName());
            return new SummaryProgress("unknown", null, "摘要状态暂时不可用，请重新查看状态。");
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
            StoredArticle article = feedStore.loadArticle(CurrentUser.id(), link);
            if (article == null) return ResponseEntity.status(404).body("文章没有保存在本地。");
            if (bookmarked) {
                feedStore.reading().saveBookmark(CurrentUser.id(), article.item().link(), article.item().title(),
                        article.routePath());
            } else {
                feedStore.reading().removeBookmark(CurrentUser.id(), article.item().link());
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
        ReaderPicks picks;
        try {
            UnreadPage stored = feedStore.unread().loadUnreadPage(CurrentUser.id(), source, q, bookmarked, before, 50);
            ReaderItems.Page cards = readerItems.load(stored.items(), CurrentUser.id());
            picks = cards.picks();
            page = new UnreadPageView(cards.items(), stored.bookmarkedLinks(), stored.sources(), source, q,
                    bookmarked, stored.totalCount(), stored.nextCursor() == null ? null
                            : unreadUrl(source, q, bookmarked, stored.nextCursor()),
                    unreadUrl(source, q, bookmarked, null), before != null,
                    aiConfigStore != null && aiConfigStore.get().enabled(), null);
        } catch (SQLException e) {
            log.warn("failed to load unread inbox: {}", e.getMessage());
            return unreadError(500, source, q, bookmarked, "待读内容暂时加载失败，请重试。");
        }
        return ResponseEntity.ok(templates.unreadPage(page, picks));
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
            List<SearchHit> hits = feedStore.searchIndex().searchHits(query, 101);
            List<SearchHit> shown = hits.stream().limit(100).toList();
            Map<String, ReaderItem> cards = new HashMap<>();
            for (ReaderItem card : readerItems.load(shown.stream().map(SearchHit::item).toList(), CurrentUser.id()).items()) {
                cards.put(card.link(), card);
            }
            List<SearchResultView> items = shown.stream().filter(hit -> cards.containsKey(hit.item().link()))
                    .map(hit -> SearchResultView.from(cards.get(hit.item().link()), hit.description(), query))
                    .toList();
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

    /**
     * Bookmarks page — lists all saved articles.
     */
    @GetMapping(value = "/reader/bookmarks", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String bookmarks() {
        List<BookmarkedItem> items = List.of();
        List<String> readLinks = null;
        if (feedStore != null) {
            try { items = feedStore.reading().loadBookmarks(CurrentUser.id()); }
            catch (Exception e) { log.warn("failed to load bookmarks: {}", e.getMessage()); }
            try { readLinks = feedStore.reading().loadReadLinks(CurrentUser.id()); }
            catch (Exception e) { log.warn("failed to load bookmark read states: {}", e.getMessage()); }
        }
        return templates.bookmarksPage(items, readLinks);
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
            if (feedStore.reading().isBookmarked(CurrentUser.id(), link)) {
                feedStore.reading().removeBookmark(CurrentUser.id(), link);
                return ResponseEntity.ok("removed");
            }
            feedStore.reading().saveBookmark(CurrentUser.id(), link,
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
            feedStore.reading().removeBookmark(CurrentUser.id(), link);
            return ResponseEntity.ok("");
        } catch (Exception e) {
            log.warn("bookmark remove failed for {}: {}", link, e.getMessage());
            return ResponseEntity.status(500).body("取消收藏失败，请稍后重试");
        }
    }

    /** Builds cards for a reader page, falling back to plain cards when the AI and label queries fail. */
    private ReaderItems.Page loadCards(List<StoredItem> stored, String page) {
        if (readerItems == null || stored.isEmpty()) return ReaderItems.bare(stored);
        try {
            return readerItems.load(stored, CurrentUser.id());
        } catch (SQLException e) {
            log.warn("failed to enrich {} cards: {}", page, e.getMessage());
            return ReaderItems.bare(stored);
        }
    }

    /** Records whether the reader agrees with a 必知/精选 label; verdict {@code none} clears it. */
    @PutMapping(value = "/reader/picks/feedback",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> setPickFeedback(@RequestParam String link, @RequestParam String kind,
                                                  @RequestParam String verdict) {
        PickKind pickKind;
        try {
            pickKind = PickKind.of(kind);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("标签类型无效。");
        }
        if (!"none".equals(verdict) && !PickFeedback.VERDICTS.contains(verdict)) {
            return ResponseEntity.badRequest().body("反馈类型无效。");
        }
        if (feedStore == null) return ResponseEntity.status(503).body("文章库暂时不可用。");
        try {
            ItemPick pick = feedStore.picks().labels(List.of(link)).get(link);
            if (pick == null || (pickKind == PickKind.MUST_KNOW ? !pick.mustKnow() : !pick.quality())) {
                return ResponseEntity.status(404).body("这篇文章没有这个标签。");
            }
            feedStore.picks().setFeedback(CurrentUser.id(), link, pickKind, "none".equals(verdict) ? null : verdict,
                    Instant.now());
            return ResponseEntity.ok(verdict);
        } catch (SQLException e) {
            log.warn("failed to save pick feedback: {}", e.toString());
            return ResponseEntity.status(500).body("反馈暂时无法保存，请重试。");
        }
    }

    @PutMapping(value = "/reader/read",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> setReadState(@RequestParam String link, @RequestParam boolean read) {
        if (ArticleNavigation.originalUrl(link) == null) return ResponseEntity.badRequest().body("文章链接无效。");
        if (feedStore == null) return ResponseEntity.status(503).body("文章库暂时不可用。");
        try {
            if (feedStore.loadArticle(CurrentUser.id(), link) == null) return ResponseEntity.status(404).body("文章没有保存在本地。");
            if (read) feedStore.reading().markRead(CurrentUser.id(), link);
            else feedStore.reading().unmarkRead(CurrentUser.id(), link);
            return ResponseEntity.ok(read ? "read" : "unread");
        } catch (SQLException e) {
            log.warn("failed to set article read state: {}", e.toString());
            return ResponseEntity.status(500).body("已读状态暂时无法确认，请重试。");
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
            if (feedStore.reading().isRead(CurrentUser.id(), link)) {
                feedStore.reading().unmarkRead(CurrentUser.id(), link);
                return ResponseEntity.ok("unread");
            }
            feedStore.reading().markRead(CurrentUser.id(), link);
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
            feedStore.reading().markAllRead(CurrentUser.id(), links);
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
                stored = feedStore.items().loadItemsForDate(targetDate);
            } catch (Exception e) {
                log.warn("failed to load items for briefing date {}: {}", targetDate, e.getMessage());
            }
        }

        LinkedHashMap<String, List<ReaderItem>> byCategory = new LinkedHashMap<>();
        for (ReaderItem item : loadCards(stored, "briefing").items()) {
            byCategory.computeIfAbsent(runtime.categoryOf(item.routePath()), k -> new ArrayList<>()).add(item);
        }

        List<BriefingSection> sections = byCategory.entrySet().stream()
                .map(e -> BriefingSection.from(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(BriefingSection::totalStoryCount).reversed())
                .toList();

        int articleCount = sections.stream().mapToInt(BriefingSection::totalCount).sum();
        return templates.briefingPage(sections, targetDate, articleCount);
    }

    // ── helpers ───────────────────────────────────────────────────────────────
}
