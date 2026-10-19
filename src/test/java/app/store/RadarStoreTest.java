package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.ai.AiConfig;
import app.core.FeedItem;
import app.radar.ProfileStore;
import app.radar.RadarConfig;
import app.radar.RadarTypes.RunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadarStoreTest {
    @TempDir Path directory;
    @Test void snapshotsAreIdempotentImmutableAndRetainBothSourcesAcrossRestart() throws Exception {
        var mapper = new ObjectMapper();
        var profiles = new ProfileStore(directory.resolve("profiles"), mapper).loadAll();
        String id;
        try (var store = new FeedStore(directory.resolve("feed.db"), mapper)) {
            store.saveItems("/first", List.of(new FeedItem("Original", "https://a.test/1", "Body", null, null, List.of())));
            store.saveItems("/second", List.of(new FeedItem("Original", "https://a.test/1", "Body", null, null, List.of())));
            var request = new RunRequest(LocalDate.now().toString(), java.time.ZoneId.systemDefault().getId(), "one", AiConfig.defaults(), Map.of());
            var first = store.createRadarRun(request, RadarConfig.defaults(), profiles);
            id = first.runId();
            assertEquals(id, store.createRadarRun(request, RadarConfig.defaults(), profiles).runId());
            assertEquals(1, first.version());
            assertThrows(IllegalArgumentException.class, () -> store.createRadarRun(new RunRequest("2026-01-01",request.zoneId(),"one",request.ai(),Map.of()),RadarConfig.defaults(),profiles));
            assertEquals(List.of("/first", "/second"), store.loadRadarRun(id).candidates().getFirst().sources());
            store.saveItems("/first", List.of(new FeedItem("Changed", "https://a.test/1", "New", null, null, List.of())));
            assertEquals("Original", store.loadRadarRun(id).candidates().getFirst().title());
            assertEquals("/first", store.loadArticle("https://a.test/1").routePath());
            assertEquals(2, store.createRadarRun(new RunRequest(request.date(), request.zoneId(), "two", request.ai(), Map.of()), RadarConfig.defaults(), profiles).version());
        }
        try (var store = new FeedStore(directory.resolve("feed.db"), mapper)) {
            assertEquals("Original", store.loadRadarRun(id).candidates().getFirst().title());
            assertFalse(store.isRead("https://a.test/1"));
        }
    }
    @Test void explicitlyCountsCandidatesOmittedByLimit() throws Exception {
        var mapper = new ObjectMapper();
        try (var store = new FeedStore(directory.resolve("feed.db"), mapper)) {
            for (int i=0; i<201; i++) store.saveItems("/a", List.of(new FeedItem("Item " + i, "https://a.test/"+i, "", null, null, List.of())));
            var run = store.createRadarRun(new RunRequest(LocalDate.now().toString(), java.time.ZoneId.systemDefault().getId(), "limit", AiConfig.defaults(), Map.of()), RadarConfig.defaults(), new ProfileStore(directory.resolve("profiles"), mapper).loadAll());
            assertEquals(201, run.totalCandidates());
            assertEquals(1, run.omitted());
            assertEquals(200, store.loadRadarRun(run.runId()).candidates().size());
        }
    }
}
