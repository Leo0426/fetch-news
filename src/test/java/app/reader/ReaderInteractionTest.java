package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.auth.UserAccount;
import app.store.FeedStore;
import app.store.ReadingState;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class ReaderInteractionTest {
    @Test
    void bookmarkRemovalFailureDoesNotTellTheBrowserToRemoveTheCard() throws Exception {
        FeedStore store = mock(FeedStore.class);
        ReadingState reading = mock(ReadingState.class);
        when(store.reading()).thenReturn(reading);
        doThrow(new SQLException("database unavailable"))
                .when(reading).removeBookmark(UserAccount.OWNER_ID, "https://example.com/article");
        ReaderResource resource = new ReaderResource(store, null, null, null, null);

        assertEquals(500, resource.removeBookmark("https://example.com/article").getStatusCode().value());
    }

    @Test
    void successfulBookmarkRemovalReturnsAnEmptyFragment() throws Exception {
        FeedStore store = mock(FeedStore.class);
        ReadingState reading = mock(ReadingState.class);
        when(store.reading()).thenReturn(reading);
        ReaderResource resource = new ReaderResource(store, null, null, null, null);

        var response = resource.removeBookmark("https://example.com/article");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("", response.getBody());
        verify(reading).removeBookmark(UserAccount.OWNER_ID, "https://example.com/article");
    }
}
