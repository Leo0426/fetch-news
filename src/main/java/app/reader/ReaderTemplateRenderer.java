package app.reader;

import app.AppRuntime;
import app.auth.CurrentUser;
import app.core.RouteConfig;
import app.store.BookmarkedItem;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders the reader pages: digest, article, briefing, bookmarks, search and radar.
 */
@Component
public class ReaderTemplateRenderer {
    private final TemplateEngine engine;
    private final AppRuntime runtime;

    public ReaderTemplateRenderer(TemplateEngine engine, AppRuntime runtime) {
        this.engine = engine;
        this.runtime = runtime;
    }

    /**
     * Reader page — daily digest view at /reader.
     *
     * @param aiEnabled       whether the AI summarize button should be shown on cards
     * @param bookmarkedLinks all bookmarked links for client-side Alpine initialisation
     */
    public String readerPage(List<ReaderGroup> groups, String date, int totalCount, int hours,
                             boolean aiEnabled, List<String> bookmarkedLinks, List<String> readLinks,
                             ReaderPicks picks) {
        List<String> uniqueTags = groups.stream()
                .map(ReaderGroup::tag).filter(t -> t != null && !t.isBlank())
                .distinct().toList();
        Context ctx = readerContext();
        ctx.setVariable("groups", groups);
        ctx.setVariable("date", date);
        ctx.setVariable("totalCount", totalCount);
        ctx.setVariable("hours", hours);
        ctx.setVariable("aiEnabled", aiEnabled);
        ctx.setVariable("bookmarkedLinks", bookmarkedLinks);
        ctx.setVariable("readLinks", readLinks);
        ctx.setVariable("uniqueTags", uniqueTags);
        ctx.setVariable("sourceLabels", sourceLabels());
        ctx.setVariable("picks", picks);
        return engine.process("reader/index", ctx);
    }

    public String articlePage(ArticlePageView page) {
        Context ctx = readerContext();
        ctx.setVariable("page", page);
        ctx.setVariable("sourceLabels", sourceLabels());
        return engine.process("reader/article", ctx);
    }

    /** Reader pages show admin links and the account menu depending on who is signed in. */
    private static Context readerContext() {
        Context ctx = new Context();
        ctx.setVariable("viewer", CurrentUser.viewer());
        return ctx;
    }

    private Map<String, String> sourceLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (RouteConfig config : runtime.routeConfigStore().list(runtime.routeRegistry().paths())) {
            if (config.alias() != null && !config.alias().isBlank()) {
                labels.put(config.path(), config.alias());
            }
        }
        return labels;
    }

    private static final DateTimeFormatter BRIEFING_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日");

    private static final DateTimeFormatter BRIEFING_ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Daily briefing page — items grouped by category for the given calendar date.
     */
    public String briefingPage(List<BriefingSection> sections, LocalDate date, int totalCount) {
        Context ctx = readerContext();
        ctx.setVariable("sections", sections);
        ctx.setVariable("date", date.format(BRIEFING_DATE_FMT));
        ctx.setVariable("dateIso", date.format(BRIEFING_ISO_FMT));
        ctx.setVariable("todayIso", LocalDate.now().format(BRIEFING_ISO_FMT));
        ctx.setVariable("prevDate", date.minusDays(1).format(BRIEFING_ISO_FMT));
        ctx.setVariable("nextDate", date.plusDays(1).format(BRIEFING_ISO_FMT));
        ctx.setVariable("isToday", date.equals(LocalDate.now()));
        ctx.setVariable("totalCount", totalCount);
        ctx.setVariable("totalStoryCount", sections.stream().mapToInt(BriefingSection::totalStoryCount).sum());
        ctx.setVariable("sourceLabels", sourceLabels());
        return engine.process("reader/briefing", ctx);
    }

    /**
     * Bookmarks page — lists all saved articles.
     */
    public String bookmarksPage(List<BookmarkedItem> items) {
        return bookmarksPage(items, null);
    }

    public String bookmarksPage(List<BookmarkedItem> items, List<String> readLinks) {
        Context ctx = readerContext();
        ctx.setVariable("items", items);
        ctx.setVariable("readLinks", readLinks == null ? List.of() : readLinks);
        ctx.setVariable("readStateAvailable", readLinks != null);
        ctx.setVariable("sourceLabels", sourceLabels());
        return engine.process("reader/bookmarks", ctx);
    }

    /**
     * Reader search results page.
     */
    public String searchPage(SearchPageView page) {
        Context ctx = readerContext();
        ctx.setVariable("page", page);
        ctx.setVariable("sourceLabels", sourceLabels());
        return engine.process("reader/search", ctx);
    }

    public String radarPage(RadarPageView page) {
        Context ctx = readerContext();
        ctx.setVariable("page", page);
        return engine.process("reader/radar", ctx);
    }
}
