package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import app.store.FeedStore;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class ReaderInteractionTest {
    @Test
    void bookmarkRemovalFailureDoesNotTellTheBrowserToRemoveTheCard() throws Exception {
        FeedStore store = mock(FeedStore.class);
        doThrow(new SQLException("database unavailable")).when(store).removeBookmark("https://example.com/article");
        ReaderResource resource = new ReaderResource(store, null, null, null, null);

        assertEquals(500, resource.removeBookmark("https://example.com/article").getStatusCode().value());
    }

    @Test
    void successfulBookmarkRemovalReturnsAnEmptyFragment() throws Exception {
        FeedStore store = mock(FeedStore.class);
        ReaderResource resource = new ReaderResource(store, null, null, null, null);

        var response = resource.removeBookmark("https://example.com/article");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("", response.getBody());
        verify(store).removeBookmark("https://example.com/article");
    }
}
