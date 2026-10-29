package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.auth.UserAccount;
import app.core.FeedItem;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReaderReadStateTest {
    @TempDir Path directory;

    @Test
    void explicitSelectionsRemainIdempotentAcrossRetriesAndStaleViews() throws Exception {
        String link = "https://example.com/article";
        try (var store = new FeedStore(directory.resolve("read.db"), new ObjectMapper())) {
            store.saveItems("/source", List.of(new FeedItem("Saved article", link, "Body",
                    Instant.parse("2026-10-01T12:00:00Z"), null, List.of())));
            var resource = reader(store);

            assertEquals("read", resource.setReadState(link, true).getBody());
            assertEquals("read", resource.setReadState(link, true).getBody());
            assertTrue(store.reading().isRead(UserAccount.OWNER_ID, link));

            assertEquals("unread", resource.setReadState(link, false).getBody());
            assertEquals("unread", resource.setReadState(link, false).getBody());
            assertFalse(store.reading().isRead(UserAccount.OWNER_ID, link));

            assertEquals("read", resource.toggleRead(link).getBody());
            assertEquals("unread", resource.toggleRead(link).getBody());
        }
    }

    @Test
    void invalidMissingAndUnavailableArticlesDoNotCreateReadMarks() throws Exception {
        String missing = "https://example.com/missing";
        assertEquals(503, reader(null).setReadState(missing, true).getStatusCode().value());
        try (var store = new FeedStore(directory.resolve("errors.db"), new ObjectMapper())) {
            var resource = reader(store);
            assertEquals(400, resource.setReadState("javascript:bad()", true).getStatusCode().value());
            assertEquals(404, resource.setReadState(missing, true).getStatusCode().value());
            assertFalse(store.reading().isRead(UserAccount.OWNER_ID, missing));
            store.close();
            assertEquals(500, resource.setReadState(missing, true).getStatusCode().value());
        }
    }

    private ReaderResource reader(FeedStore store) {
        return new ReaderResource(store, null, null, null, null);
    }
}
