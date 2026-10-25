package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.ai.AiConfig;
import app.auth.UserAccount;
import app.core.FeedItem;
import app.radar.BuiltInProfiles;
import app.radar.RadarConfig;
import app.radar.RadarTypes.RunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadarStoreTest {
    @TempDir Path directory;
    @Test void snapshotsAreIdempotentImmutableAndRetainBothSourcesAcrossRestart() throws Exception {
        var mapper = new ObjectMapper();
        var profiles = new BuiltInProfiles(mapper).all();
        String id;
        try (var store = new FeedStore(directory.resolve("feed.db"), mapper)) {
            store.saveItems("/first", List.of(new FeedItem("Original", "https://a.test/1", "Body", null, null, List.of())));
            store.saveItems("/second", List.of(new FeedItem("Original", "https://a.test/1", "Body", null, null, List.of())));
            var request = new RunRequest(LocalDate.now().toString(), ZoneId.systemDefault().getId(), "one", AiConfig.defaults(), Map.of());
            var first = store.radar().createRun(request, RadarConfig.BUILT_IN, profiles);
            id = first.runId();
            assertEquals(id, store.radar().createRun(request, RadarConfig.BUILT_IN, profiles).runId());
            assertEquals(1, first.version());
            assertThrows(IllegalArgumentException.class, () -> store.radar().createRun(new RunRequest("2026-01-01",request.zoneId(),"one",request.ai(),Map.of()),RadarConfig.BUILT_IN,profiles));
            assertEquals(List.of("/first", "/second"), store.radar().loadRun(id).candidates().getFirst().sources());
            store.saveItems("/first", List.of(new FeedItem("Changed", "https://a.test/1", "New", null, null, List.of())));
            assertEquals("Original", store.radar().loadRun(id).candidates().getFirst().title());
            assertEquals("/first", store.loadArticle(UserAccount.OWNER_ID, "https://a.test/1").routePath());
            assertEquals(2, store.radar().createRun(new RunRequest(request.date(), request.zoneId(), "two", request.ai(), Map.of()), RadarConfig.BUILT_IN, profiles).version());
        }
        try (var store = new FeedStore(directory.resolve("feed.db"), mapper)) {
            assertEquals("Original", store.radar().loadRun(id).candidates().getFirst().title());
            assertFalse(store.reading().isRead(UserAccount.OWNER_ID, "https://a.test/1"));
        }
    }
    @Test void explicitlyCountsCandidatesOmittedByLimit() throws Exception {
        var mapper = new ObjectMapper();
        try (var store = new FeedStore(directory.resolve("feed.db"), mapper)) {
            for (int i=0; i<201; i++) store.saveItems("/a", List.of(new FeedItem("Item " + i, "https://a.test/"+i, "", null, null, List.of())));
            var run = store.radar().createRun(new RunRequest(LocalDate.now().toString(), ZoneId.systemDefault().getId(), "limit", AiConfig.defaults(), Map.of()), RadarConfig.BUILT_IN, new BuiltInProfiles(mapper).all());
            assertEquals(201, run.totalCandidates());
            assertEquals(1, run.omitted());
            assertEquals(200, store.radar().loadRun(run.runId()).candidates().size());
        }
    }
}
