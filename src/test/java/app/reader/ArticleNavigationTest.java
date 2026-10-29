package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ArticleNavigationTest {
    @Test
    void preservesAllowedListQueryAndFragmentWithoutDoubleEncoding() {
        for (String value : new String[]{"/reader?hours=72", "/reader/search?q=%E6%A8%A1%E5%9E%8B#rd-main",
                "/reader/search?q=模型%20AI", "/reader/bookmarks", "/reader/briefing?date=2026-10-03"}) {
            assertEquals(value, ArticleNavigation.returnUrl(value));
        }
        for (String value : new String[]{null, "", "https://evil.invalid/reader", "//evil.invalid", "/readerx",
                "/reader/unread", "/reader/unread?source=%2Fhn%2Ftop", "/reader/article", "/reader/../index",
                "/reader\\evil", "/reader\n", "/reader?bad=%ZZ", "/%2f%2fevil.invalid"}) {
            assertEquals("/reader", ArticleNavigation.returnUrl(value));
        }
        assertEquals("https://example.com/文章?x=1&y=2#part", ArticleNavigation.originalUrl("https://example.com/文章?x=1&y=2#part"));
        for (String value : new String[]{null, "", "javascript:alert(1)", "data:text/html,hi", "//example.com", "/path", "https:///missing-host", "https://example.com\n"}) {
            assertNull(ArticleNavigation.originalUrl(value));
        }
    }
}
