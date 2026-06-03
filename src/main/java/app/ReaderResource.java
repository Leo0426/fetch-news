package app;

import app.ai.AiConfigStore;
import app.ai.ArticleSummarizer;
import app.core.FeedItem;
import app.reader.ReaderGroup;
import app.reader.ReaderItem;
import app.store.BookmarkedItem;
import app.store.FeedStore;
import app.store.StoredItem;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

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
        if (feedStore != null && !stored.isEmpty()) {
            try {
                List<String> links = stored.stream()
                        .filter(s -> s.link() != null)
                        .map(app.store.StoredItem::link)
                        .toList();
                summaries = feedStore.loadSummaries(links);
            } catch (Exception e) {
                log.warn("failed to load summaries: {}", e.getMessage());
            }
        }

        Map<String, String> categories = runtime.routeRegistry().categories();
        // Group by routePath (concrete source), preserving DB insertion order (newest first)
        LinkedHashMap<String, List<ReaderItem>> byRoute = new LinkedHashMap<>();
        final java.util.Map<String, String> finalSummaries = summaries;

        for (StoredItem s : stored) {
            if (s.title() == null || s.title().isBlank()) continue;
            if (s.link()  == null || s.link().isBlank())  continue;
            String summary = finalSummaries.get(s.link());
            byRoute.computeIfAbsent(s.routePath(), k -> new ArrayList<>())
                    .add(new ReaderItem(s.routePath(), s.title(), s.link(), s.firstSeenAt(), summary));
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

    /**
     * Full-text search across cached item titles and AI summaries.
     *
     * @param q search query (minimum 2 characters)
     */
    @GetMapping(value = "/reader/search", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String search(@RequestParam(defaultValue = "") String q) {
        q = q.strip();
        List<ReaderItem> items = List.of();
        if (q.length() >= 2 && feedStore != null) {
            try {
                List<StoredItem> stored = feedStore.search(q, 100);
                if (!stored.isEmpty()) {
                    List<String> links = stored.stream()
                            .filter(s -> s.link() != null).map(StoredItem::link).toList();
                    java.util.Map<String, String> summaries = feedStore.loadSummaries(links);
                    items = stored.stream()
                            .map(s -> new ReaderItem(
                                    s.routePath(), s.title(), s.link(),
                                    s.firstSeenAt(), summaries.get(s.link())))
                            .toList();
                }
            } catch (Exception e) {
                log.warn("search failed for '{}': {}", q, e.getMessage());
            }
        }
        return templates.searchPage(q, items);
    }

    /**
     * Generates (or returns cached) an AI summary for a single article.
     *
     * <p>Returns the summary as plain text on success, or an error message on failure.
     * The summary is persisted so subsequent calls return the cached result immediately.
     */
    @PostMapping(
            value = "/reader/summarize",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> summarize(
            @RequestParam String link,
            @RequestParam(required = false, defaultValue = "") String title) {
        if (link == null || link.isBlank()) {
            return ResponseEntity.badRequest().body("缺少 link 参数");
        }
        if (aiConfigStore == null || !aiConfigStore.get().enabled()) {
            return ResponseEntity.badRequest().body("AI 功能未启用，请先在后台配置 Ollama");
        }
        try {
            // Return cached summary when available
            String cached = feedStore.loadSummary(link);
            if (cached != null && !cached.isBlank()) {
                return ResponseEntity.ok(cached);
            }
            // Generate and persist a new summary
            FeedItem item = new FeedItem(
                    title.isBlank() ? null : title, link, null, null, null, List.of());
            articleSummarizer.summarize(List.of(item));

            String result = feedStore.loadSummary(link);
            if (result == null || result.isBlank()) {
                return ResponseEntity.status(500).body("摘要生成失败，请确认 Ollama 服务正常");
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("manual summarize failed for {}: {}", link, e.getMessage());
            return ResponseEntity.status(500).body("生成失败：" + e.getMessage());
        }
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
            return ResponseEntity.ok("");
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
