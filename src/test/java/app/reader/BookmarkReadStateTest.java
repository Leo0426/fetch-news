package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.AppRuntime;
import app.TestPages;
import app.auth.UserAccount;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BookmarkReadStateTest {
    @TempDir Path directory;
    private FeedStore store;
    private ReaderResource resource;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        store = new FeedStore(directory.resolve("bookmarks.db"), mapper);
        RouteConfigStore configs = new RouteConfigStore(directory.resolve("routes.json"), mapper);
        configs.save(new RouteConfig("/saved", "/rss", true, 300, 1800, 0, null,
                "https://example.com/feed", "我的收藏来源"));
        AppRuntime runtime = TestPages.runtime(configs, new Route("/rss", context -> { throw new AssertionError("No upstream calls expected"); }));
        var engine = TestPages.engine();
        ReaderTemplateRenderer renderer = new ReaderTemplateRenderer(engine, runtime);
        resource = new ReaderResource(store, runtime, renderer, null, null);
    }

    @AfterEach
    void closeStore() throws Exception {
        store.close();
    }

    @Test
    void rendersSavedReadStatesWithoutChangingBookmarksOrMarkingInternalLinksRead() throws Exception {
        String read = "https://example.com/read";
        String unread = "https://example.com/unread?x=1&y=2";
        store.reading().saveBookmark(UserAccount.OWNER_ID, read, "Read article", "/saved");
        store.reading().saveBookmark(UserAccount.OWNER_ID, unread, "Unread article", "/saved");
        store.reading().markRead(UserAccount.OWNER_ID, read);

        Document page = Jsoup.parse(resource.bookmarks());
        var cards = page.select(".bm-card[data-link]");
        assertEquals(2, cards.size());
        var readCard = cards.stream().filter(card -> card.attr("data-link").equals(read)).findFirst().orElseThrow();
        var unreadCard = cards.stream().filter(card -> card.attr("data-link").equals(unread)).findFirst().orElseThrow();
        assertEquals("true", readCard.attr("data-read"));
        assertEquals("已读", readCard.select("[data-bookmark-read-state]").text());
        assertEquals("false", unreadCard.attr("data-read"));
        assertEquals("未读", unreadCard.select("[data-bookmark-read-state]").text());
        assertEquals(unread, unreadCard.select(".bm-card-title").attr("href"));
        assertEquals("_blank", unreadCard.select(".bm-card-title").attr("target"));
        assertEquals("我的收藏来源", unreadCard.select(".bm-src").text());
        assertNotNull(unreadCard.selectFirst("[data-article-link][href^='/reader/article?']"));
        assertEquals("/reader/bookmark", unreadCard.select(".bm-remove").attr("hx-delete"));
        assertFalse(store.reading().isRead(UserAccount.OWNER_ID, unread), "rendering bookmarks and internal reading links is read-only");
        assertEquals(2, store.reading().loadBookmarks(UserAccount.OWNER_ID).size());
    }

    @Test
    void repeatedOriginalClicksStayReadAndStillAllowRemovingTheBookmark() throws Exception {
        String link = "https://example.com/repeated";
        store.reading().saveBookmark(UserAccount.OWNER_ID, link, "Repeated original", "/saved");
        var originalBookmarks = store.reading().loadBookmarks(UserAccount.OWNER_ID);

        assertEquals(200, resource.markAllRead(List.of(link)).getStatusCode().value());
        assertEquals(200, resource.markAllRead(List.of(link)).getStatusCode().value());

        assertTrue(store.reading().isRead(UserAccount.OWNER_ID, link));
        assertEquals(originalBookmarks, store.reading().loadBookmarks(UserAccount.OWNER_ID));
        Document page = Jsoup.parse(resource.bookmarks());
        assertEquals("true", page.select(".bm-card").attr("data-read"));
        assertEquals("已读", page.select("[data-bookmark-read-state]").text());
        assertEquals(200, resource.removeBookmark(link).getStatusCode().value());
        assertTrue(store.reading().loadBookmarks(UserAccount.OWNER_ID).isEmpty());
        assertTrue(store.reading().isRead(UserAccount.OWNER_ID, link));
    }

    @Test
    void failedReadSaveKeepsTheBookmarkUnread() throws Exception {
        String link = "https://example.com/write-failure";
        store.reading().saveBookmark(UserAccount.OWNER_ID, link, "Write failure", "/saved");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("bookmarks.db"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_read BEFORE INSERT ON read_items "
                    + "BEGIN SELECT RAISE(ABORT, 'test unavailable'); END");
        }

        assertEquals(500, resource.markAllRead(List.of(link)).getStatusCode().value());

        assertFalse(store.reading().isRead(UserAccount.OWNER_ID, link));
        Document page = Jsoup.parse(resource.bookmarks());
        assertEquals("false", page.select(".bm-card").attr("data-read"));
        assertEquals("未读", page.select("[data-bookmark-read-state]").text());
        assertNotNull(page.selectFirst("[data-bookmark-read-error][hidden] [data-bookmark-read-retry]"));
        assertEquals(1, store.reading().loadBookmarks(UserAccount.OWNER_ID).size());
    }

    @Test
    void unavailableReadStateDoesNotMisrepresentSavedBookmarksAsUnread() throws Exception {
        String link = "https://example.com/read-unavailable";
        store.reading().saveBookmark(UserAccount.OWNER_ID, link, "Read state unavailable", "/saved");
        store.reading().markRead(UserAccount.OWNER_ID, link);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("bookmarks.db"));
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE read_items");
        }

        Document page = Jsoup.parse(resource.bookmarks());

        assertEquals("unknown", page.select(".bm-card").attr("data-read"));
        assertEquals("状态待确认", page.select("[data-bookmark-read-state]").text());
        assertEquals(link, page.select(".bm-card-title").attr("href"));
        assertEquals(1, store.reading().loadBookmarks(UserAccount.OWNER_ID).size());
    }
}
