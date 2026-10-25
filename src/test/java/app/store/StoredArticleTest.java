package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.auth.UserAccount;
import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoredArticleTest {
    @TempDir Path directory;

    @Test
    void readingStoredArticleCombinesContentAndStateWithoutChangingIt() throws Exception {
        try (var store = new FeedStore(directory.resolve("feed.db"), new ObjectMapper())) {
            var item = new FeedItem("Local article", "https://example.com/正文?x=1&y=2", "<p>Saved body</p>",
                    Instant.parse("2026-10-03T00:00:00Z"), "Writer", List.of("技术"));
            store.saveItems("/original", List.of(item));
            store.saveItems("/other", List.of(item));
            store.aiCache().saveSummary(item.link(), "AI summary", "fixture");
            store.aiCache().saveExtract(item.link(), "AI extract", "fixture");
            var article = store.loadArticle(UserAccount.OWNER_ID, item.link());
            assertNotNull(article);
            assertEquals(item, article.item());
            assertEquals("/original", article.routePath());
            assertNotNull(article.firstSeenAt());
            assertEquals("AI summary", article.summary());
            assertEquals("AI extract", article.extract());
            assertFalse(article.read());
            assertFalse(article.bookmarked());
            assertFalse(store.reading().isRead(UserAccount.OWNER_ID, item.link()));
            store.reading().markRead(UserAccount.OWNER_ID, item.link());
            store.reading().saveBookmark(UserAccount.OWNER_ID, item.link(), item.title(), "/original");
            assertTrue(store.loadArticle(UserAccount.OWNER_ID, item.link()).read());
            assertTrue(store.loadArticle(UserAccount.OWNER_ID, item.link()).bookmarked());
            assertNull(store.loadArticle(UserAccount.OWNER_ID, "https://example.com/unknown"));
            store.reading().saveBookmark(UserAccount.OWNER_ID, "https://example.com/orphan", "Old bookmark", "/original");
            assertNull(store.loadArticle(UserAccount.OWNER_ID, "https://example.com/orphan"));
        }
    }
}
