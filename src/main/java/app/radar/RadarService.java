package app.radar;

import app.ai.AiConfigStore;
import app.radar.RadarTypes.RunRequest;
import app.radar.RadarTypes.RunStatus;
import app.store.FeedStore;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

public class RadarService {
    private final FeedStore store;
    private final RadarConfig config;
    private final BuiltInProfiles profiles;
    private final AiConfigStore ai;
    private final Supplier<Map<String, String>> categories;

    public RadarService(
            FeedStore store,
            RadarConfig config,
            BuiltInProfiles profiles,
            AiConfigStore ai,
            Supplier<Map<String, String>> categories) {
        this.store = store;
        this.config = config;
        this.profiles = profiles;
        this.ai = ai;
        this.categories = categories;
    }

    public RunStatus submit(String date, String zone, String key) throws Exception {
        return submit(new RunRequest(date, zone, key, ai.get(), categories.get()));
    }

    public RunStatus submit(RunRequest request) throws Exception {
        checkAvailable();
        var definitions = profiles.all();
        config.validateProfiles(definitions.keySet());
        return store.radar()
                .createRun(
                        new RunRequest(
                                request.date(), request.zoneId(), request.idempotencyKey(), ai.get(), categories.get()),
                        config,
                        definitions);
    }

    public RunStatus status(String id) throws SQLException {
        if (store == null) throw new IllegalStateException("Article storage unavailable");
        var run = store.radar().loadRun(id);
        if (run == null) throw new NoSuchElementException("Radar run not found");
        return run.status();
    }

    public synchronized RunStatus resume(String id) throws SQLException {
        checkAvailable();
        var run = store.radar().loadRun(id);
        if (run == null) throw new NoSuchElementException("Radar run not found");
        if (!run.request().ai().sameModel(ai.get()))
            throw new IllegalStateException("Restore the original model to resume, or create a new run");
        if (run.status().state().equals("paused"))
            store.radar().updateState(id, "queued", run.status().stage(), null);
        return status(id);
    }

    public String fingerprint() throws IOException {
        return RadarJson.hash(List.of(config, profiles.all(), ai.get().identity()));
    }

    /** Reports follow the global AI switch. */
    public boolean enabled() {
        return config.enabled() && ai.get().enabled();
    }

    private void checkAvailable() {
        if (store == null) throw new IllegalStateException("Article storage unavailable");
        if (!enabled()) throw new IllegalStateException("Radar and AI must be enabled");
    }
}
