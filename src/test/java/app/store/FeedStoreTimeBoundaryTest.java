package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.core.FeedItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeedStoreTimeBoundaryTest {
    private static final String ROUTE = "/articles";
    private static final String LINK_PREFIX = "https://example.com/";

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void excludesEarlierMixedPrecisionItemsAtNanosecondBoundary(boolean multipleRoutes) throws Exception {
        try (FeedStore store = new FeedStore(database(), new ObjectMapper())) {
            seed(store, Map.of(
                    "whole-second", "2026-10-04T15:31:57Z",
                    "milliseconds", "2026-10-04T15:31:57.808Z",
                    "microseconds", "2026-10-04T15:31:57.808123Z",
                    "equal", "2026-10-04T15:31:57.808123001Z",
                    "later", "2026-10-04T15:31:57.808123002Z"));

            assertEquals(List.of(LINK_PREFIX + "later", LINK_PREFIX + "equal"),
                    loadLinksSince(store, multipleRoutes, "2026-10-04T15:31:57.808123001Z"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void includesLaterFractionsAtMillisecondBoundary(boolean multipleRoutes) throws Exception {
        try (FeedStore store = new FeedStore(database(), new ObjectMapper())) {
            seed(store, Map.of(
                    "earlier", "2026-10-04T15:31:57.807999999Z",
                    "equal", "2026-10-04T15:31:57.808Z",
                    "nanosecond-later", "2026-10-04T15:31:57.808000001Z",
                    "microsecond-later", "2026-10-04T15:31:57.808001Z"));

            assertEquals(List.of(
                            LINK_PREFIX + "microsecond-later", LINK_PREFIX + "nanosecond-later", LINK_PREFIX + "equal"),
                    loadLinksSince(store, multipleRoutes, "2026-10-04T15:31:57.808Z"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void includesWholeSecondAndLaterFractionsAtWholeSecondBoundary(boolean multipleRoutes) throws Exception {
        try (FeedStore store = new FeedStore(database(), new ObjectMapper())) {
            seed(store, Map.of(
                    "previous-second", "2026-10-04T15:31:56.999999999Z",
                    "equal", "2026-10-04T15:31:57Z",
                    "nanosecond-later", "2026-10-04T15:31:57.000000001Z",
                    "later", "2026-10-04T15:31:57.100Z"));

            assertEquals(List.of(LINK_PREFIX + "later", LINK_PREFIX + "nanosecond-later", LINK_PREFIX + "equal"),
                    loadLinksSince(store, multipleRoutes, "2026-10-04T15:31:57Z"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordersMixedPrecisionItemsByActualInstant(boolean multipleRoutes) throws Exception {
        try (FeedStore store = new FeedStore(database(), new ObjectMapper())) {
            seed(store, Map.of(
                    "whole-second", "2026-10-04T15:31:57Z",
                    "milliseconds", "2026-10-04T15:31:57.808Z",
                    "microseconds", "2026-10-04T15:31:57.808001Z",
                    "nanoseconds", "2026-10-04T15:31:57.808001001Z"));

            assertEquals(List.of(
                            LINK_PREFIX + "nanoseconds", LINK_PREFIX + "microseconds",
                            LINK_PREFIX + "milliseconds", LINK_PREFIX + "whole-second"),
                    loadLinksSince(store, multipleRoutes, "2026-10-04T15:31:56Z"));
        }
    }

    private void seed(FeedStore store, Map<String, String> timestamps) throws Exception {
        store.saveItems(ROUTE, timestamps.keySet().stream()
                .map(slug -> new FeedItem(slug, LINK_PREFIX + slug, "Body", null, null, List.of()))
                .toList());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.prepareStatement("UPDATE feed_items SET first_seen_at = ? WHERE item_link = ?")) {
            for (Map.Entry<String, String> entry : timestamps.entrySet()) {
                statement.setString(1, entry.getValue());
                statement.setString(2, LINK_PREFIX + entry.getKey());
                statement.executeUpdate();
            }
        }
    }

    private static List<String> loadLinksSince(FeedStore store, boolean multipleRoutes, String since) throws Exception {
        List<StoredItem> items = multipleRoutes
                ? store.items().loadNewItemsSinceForRoutes(List.of(ROUTE, "/missing"), Instant.parse(since))
                : store.items().loadNewItemsSince(ROUTE, Instant.parse(since));
        return items.stream().map(StoredItem::link).toList();
    }

    private Path database() {
        return tempDir.resolve("feed.db");
    }
}
