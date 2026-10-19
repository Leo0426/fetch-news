package app.radar;

import app.ai.AiConfigStore;
import app.radar.RadarTypes.RunRequest;
import app.radar.RadarTypes.RunStatus;
import app.store.FeedStore;
import java.sql.SQLException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

public class RadarService {
    private final FeedStore store;
    private final RadarConfigStore config;
    private final ProfileStore profiles;
    private final AiConfigStore ai;
    private final Supplier<Map<String,String>> categories;

    public RadarService(FeedStore store,RadarConfigStore config,ProfileStore profiles,AiConfigStore ai,Supplier<Map<String,String>> categories) {
        this.store=store; this.config=config; this.profiles=profiles; this.ai=ai; this.categories=categories;
    }
    public RunStatus submit(String date,String zone,String key) throws Exception {
        return submit(new RunRequest(date,zone,key,ai.get(),categories.get()));
    }
    public RunStatus submit(RunRequest request) throws Exception {
        checkAvailable();
        var definitions=profiles.loadAll(); config.get().validateProfiles(definitions.keySet());
        return store.createRadarRun(new RunRequest(request.date(),request.zoneId(),request.idempotencyKey(),ai.get(),categories.get()),config.get(),definitions);
    }
    public RunStatus status(String id) throws SQLException {
        if(store==null) throw new IllegalStateException("Article storage unavailable");
        var run=store.loadRadarRun(id);
        if(run==null) throw new NoSuchElementException("Radar run not found");
        return run.status();
    }
    public synchronized RunStatus resume(String id) throws SQLException {
        checkAvailable();
        var run=store.loadRadarRun(id);
        if(run==null) throw new NoSuchElementException("Radar run not found");
        if(!run.request().ai().host().equals(ai.get().host()) || !run.request().ai().model().equals(ai.get().model()))
            throw new IllegalStateException("Restore the original model to resume, or create a new run");
        if(run.status().state().equals("paused")) store.updateRadarState(id,"queued",run.status().stage(),null);
        return status(id);
    }
    private void checkAvailable() {
        if(store==null) throw new IllegalStateException("Article storage unavailable");
        if(!config.get().enabled() || !ai.get().enabled()) throw new IllegalStateException("Radar and AI must be enabled");
    }
}
