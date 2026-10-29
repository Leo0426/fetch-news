package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.auth.UserAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadingStateTest {
    @TempDir
    Path tempDir;

    @Test
    void batchReadFailureDoesNotCommitEarlierLinks() throws Exception {
        Path database = tempDir.resolve("feed.db");
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            store.reading().markRead(UserAccount.OWNER_ID, "https://example.com/existing");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER reject_read BEFORE INSERT ON read_items
                        WHEN NEW.item_link = 'https://example.com/rejected'
                        BEGIN SELECT RAISE(ABORT, 'rejected for test'); END
                        """);
            }

            assertThrows(SQLException.class, () -> store.reading().markAllRead(UserAccount.OWNER_ID, List.of(
                    "https://example.com/first", "https://example.com/rejected", "https://example.com/last")));

            assertEquals(List.of("https://example.com/existing"), store.reading().loadReadLinks(UserAccount.OWNER_ID));
        }
    }

    @Test
    void batchReadSupportsFiveHundredLinksAndRepeatedLinks() throws Exception {
        try (FeedStore store = new FeedStore(tempDir.resolve("feed.db"), new ObjectMapper())) {
            List<String> links = IntStream.range(0, 500).mapToObj(i -> "https://example.com/" + i).toList();
            store.reading().markAllRead(UserAccount.OWNER_ID, links);
            store.reading().markAllRead(UserAccount.OWNER_ID, List.of(links.getFirst(), links.getFirst()));
            store.reading().markAllRead(UserAccount.OWNER_ID, List.of());

            assertEquals(Set.copyOf(links), Set.copyOf(store.reading().loadReadLinks(UserAccount.OWNER_ID)));
            assertEquals(500, store.reading().loadReadLinks(UserAccount.OWNER_ID).size());
        }
    }
}
