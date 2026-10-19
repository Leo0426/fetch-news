package app.radar;

import app.ai.AiConfigStore;
import app.ai.ArticleExtractor;
import app.ai.OllamaClient;
import app.radar.RadarTypes.Analysis;
import app.radar.RadarTypes.Decision;
import app.radar.RadarTypes.Event;
import app.radar.RadarTypes.Report;
import app.radar.RadarTypes.ReportStory;
import app.radar.RadarTypes.RunSnapshot;
import app.radar.RadarTypes.Selection;
import app.store.FeedStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RadarWorker implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(RadarWorker.class);
    private final FeedStore store;
    private final RadarConfigStore config;
    private final AiConfigStore ai;
    private final OllamaClient client;
    private final ArticleExtractor extractor;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("radar-worker").factory());
    private final AtomicBoolean processing=new AtomicBoolean();
    private volatile boolean closed;
    private boolean started;

    public RadarWorker(FeedStore store,RadarConfigStore config,AiConfigStore ai,OllamaClient client,
                       ArticleExtractor extractor,ObjectMapper mapper,Clock clock) throws java.sql.SQLException {
        this.store=store;this.config=config;this.ai=ai;this.client=client;this.extractor=extractor;this.mapper=mapper;this.clock=clock;
        if(store!=null) store.recoverRadarRuns();
    }
    public synchronized void start() {
        if(started || closed || store==null) return;
        started=true; executor.scheduleWithFixedDelay(this::processNext,0,1,TimeUnit.SECONDS);
    }
    public void processNext() {
        if(closed || store==null || !config.get().enabled() || !ai.get().enabled() || !processing.compareAndSet(false,true)) return;
        RunSnapshot run=null;
        long startedAt=clock.millis();
        try {
            run=store.claimNextRadarRun();
            if(run==null) return;
            store.saveRadarCheckpoint(run.status().runId(),"attemptStartedAt",Long.toString(startedAt));
            RunSnapshot claimed=run;
            long previous=Long.parseLong(run.checkpoints().getOrDefault("elapsedMillis","0"));
            try(var scope=client.withScope(run.request().ai(),()->guard(claimed,startedAt,previous,true), metrics -> store.recordRadarMetrics(claimed.status().runId(), metrics))) {
                execute(run,startedAt,previous);
            }
        } catch(RadarControlException e) {
            try {
                if(run!=null) {
                    if(e.state().equals("partial")) publishPartial(run,e.getMessage());
                    else store.updateRadarState(run.status().runId(),"paused",store.loadRadarRun(run.status().runId()).status().stage(),e.getMessage());
                }
            } catch(Exception failure) { fail(run,failure); }
        } catch(Exception e) { fail(run,e); }
        finally {
            if(run!=null) {
                try {
                    long prior=Long.parseLong(run.checkpoints().getOrDefault("elapsedMillis","0"));
                    store.saveRadarCheckpoint(run.status().runId(),"elapsedMillis",Long.toString(prior+Math.max(0,clock.millis()-startedAt)));
                    store.saveRadarCheckpoint(run.status().runId(),"attemptStartedAt","0");
                } catch(Exception e) { log.warn("Cannot save radar runtime: {}",e.getClass().getSimpleName()); }
            }
            processing.set(false);
        }
    }
    private void guard(RunSnapshot run,long start,long prior,boolean modelCall) throws Exception {
        if(closed || Thread.currentThread().isInterrupted()) throw new RadarControlException("paused","Worker stopped; resume explicitly");
        if(!config.get().enabled() || !ai.get().enabled()) throw new RadarControlException("paused","Radar or AI disabled");
        if(!run.request().ai().sameModel(ai.get()))
            throw new RadarControlException("paused","Model configuration changed");
        if(prior+clock.millis()-start>=TimeUnit.MINUTES.toMillis(run.config().runTimeoutMinutes()))
            throw new RadarControlException("partial","Run time budget exhausted");
        if(modelCall) {
            if(store.loadRadarRun(run.status().runId()).status().calls()>=run.config().maxModelCalls())
                throw new RadarControlException("partial","Model call budget exhausted");
            store.incrementRadarCalls(run.status().runId());
        }
    }
    private void execute(RunSnapshot run,long start,long prior) throws Exception {
        String id=run.status().runId();
        Map<String,String> checkpoints=new LinkedHashMap<>(run.checkpoints());
        var router=new ProfileRouter(client,mapper);
        var analyzer=new ArticleAnalyzer(client,store,mapper);
        List<Analysis> analyses=new ArrayList<>();
        for(var candidate:run.candidates()) {
            guard(run,start,prior,false);
            String key="analysis:"+candidate.link();
            Analysis analysis;
            if(checkpoints.containsKey(key)) analysis=mapper.readValue(checkpoints.get(key),Analysis.class);
            else {
                String profile=run.config().defaultProfile();
                try {
                    stage(id,"routing");
                    String routing="profile:"+candidate.link();
                    profile=checkpoints.get(routing);
                    if(profile==null) { profile=router.resolve(candidate,run); save(id,checkpoints,routing,profile); }
                    stage(id,"scoring");
                    analysis=analyzer.analyze(candidate,run.profiles().get(profile),run);
                    store.saveRadarAnalysis(id,analysis);
                } catch(RadarControlException e) { throw e; }
                catch(java.sql.SQLException e) { throw e; }
                catch(Exception e) {
                    guard(run,start,prior,false);
                    analysis=new Analysis(candidate.link(),profile==null ? run.config().defaultProfile() : profile,router.category(candidate,run),null,
                            "Analysis failed","",List.of(),true,RadarJson.hash(List.of(id,candidate.link())),message(e));
                }
                save(id,checkpoints,key,mapper.writeValueAsString(analysis));
            }
            analyses.add(analysis);
        }
        stage(id,"dedup");
        List<Analysis> passed=analyses.stream().filter(a->a.error()==null && a.score()!=null && a.score()>=run.config().settings(a.profile()).threshold()).toList();
        List<String> warnings=new ArrayList<>();
        List<Event> events;
        if(checkpoints.containsKey("events")) events=mapper.readValue(checkpoints.get("events"),new TypeReference<List<Event>>(){});
        else {
            events=new EventDeduplicator(client,mapper,warnings::add).group(passed,run);
            save(id,checkpoints,"events",mapper.writeValueAsString(events));
            if(!warnings.isEmpty()) save(id,checkpoints,"warnings",mapper.writeValueAsString(warnings));
        }
        stage(id,"select");
        Selection selection=new DigestSelector().select(events,run.config());
        List<Decision> decisions=new ArrayList<>(selection.decisions());
        for(var analysis:analyses) {
            if(analysis.error()!=null) decisions.add(new Decision(analysis.link(),"analysis_failed",null));
            else if(analysis.score()<run.config().settings(analysis.profile()).threshold()) decisions.add(new Decision(analysis.link(),"below_threshold",null));
        }
        store.saveRadarDecisions(id,decisions);
        stage(id,"enrich");
        var generator=new BlockGenerator(client,extractor,mapper);
        List<ReportStory> stories=new ArrayList<>();
        for(var event:selection.selected()) {
            guard(run,start,prior,false);
            String key="story:"+event.id();
            ReportStory story;
            if(checkpoints.containsKey(key)) story=mapper.readValue(checkpoints.get(key),ReportStory.class);
            else {
                try { story=new ReportStory(event,generator.generate(event,run.profiles().get(event.primary().profile()),run),null); }
                catch(RadarControlException e) { throw e; }
                catch(java.sql.SQLException e) { throw e; }
                catch(BlockGenerator.PartialBlocksException e) { guard(run,start,prior,false); story=new ReportStory(event,e.blocks(),message(e)); }
                catch(Exception e) { guard(run,start,prior,false); story=new ReportStory(event,Map.of(),message(e)); }
                save(id,checkpoints,key,mapper.writeValueAsString(story));
            }
            stories.add(story);
        }
        int failures=(int)analyses.stream().filter(a->a.error()!=null).count()+(int)stories.stream().filter(s->s.error()!=null).count();
        if(checkpoints.containsKey("warnings")) failures++;
        guard(run,start,prior,false);
        publish(run,stories,decisions,failures,checkpoints.containsKey("warnings") ? Map.of("dedup",checkpoints.get("warnings")) : Map.of());
    }
    private void publishPartial(RunSnapshot original,String reason) throws Exception {
        var run=store.loadRadarRun(original.status().runId());
        List<Analysis> analyses=new ArrayList<>();
        List<Decision> decisions=new ArrayList<>();
        for(var candidate:run.candidates()) {
            String saved=run.checkpoints().get("analysis:"+candidate.link());
            if(saved==null) decisions.add(new Decision(candidate.link(),"budget_exhausted",null));
            else analyses.add(mapper.readValue(saved,Analysis.class));
        }
        List<Event> events;
        if(run.checkpoints().containsKey("events")) events=mapper.readValue(run.checkpoints().get("events"),new TypeReference<List<Event>>(){});
        else events=analyses.stream().filter(a->a.score()!=null && a.error()==null).map(a->new Event(RadarJson.hash(a.link()),a,List.of())).toList();
        Selection selection=new DigestSelector().select(events,run.config()); decisions.addAll(selection.decisions());
        java.util.Set<String> decided=decisions.stream().map(Decision::link).collect(java.util.stream.Collectors.toSet());
        for(var analysis:analyses) {
            if(!decided.contains(analysis.link())) decisions.add(new Decision(analysis.link(),analysis.error()!=null ? "analysis_failed" : "below_threshold",null));
        }
        List<ReportStory> stories=new ArrayList<>();
        for(var event:selection.selected()) {
            String saved=run.checkpoints().get("story:"+event.id());
            stories.add(saved==null ? new ReportStory(event,Map.of(),"Generation budget exhausted") : mapper.readValue(saved,ReportStory.class));
        }
        store.saveRadarDecisions(run.status().runId(),decisions);
        int failures=(int)decisions.stream().filter(d->d.reason().equals("analysis_failed") || d.reason().equals("budget_exhausted")).count()
                +(int)stories.stream().filter(story->story.error()!=null).count();
        publish(run,stories,decisions,Math.max(1,failures),Map.of("budget",reason));
    }
    private void publish(RunSnapshot run,List<ReportStory> stories,List<Decision> decisions,int failures,Map<String,String> diagnostics) throws Exception {
        String id=run.status().runId();
        stage(id,"render");
        Map<String,String> metadata=new LinkedHashMap<>(diagnostics);
        metadata.put("model",run.request().ai().model()); metadata.put("configHash",RadarJson.hash(run.config()));
        metadata.put("profileHash",RadarJson.hash(run.profiles()));
        Map<String,String> titles=new LinkedHashMap<>();
        for(var candidate:run.candidates()) titles.put(candidate.link(),candidate.title());
        var report=new Report(id,run.request().date(),run.request().zoneId(),stories,decisions,run.status().totalCandidates(),run.status().omitted(),failures,metadata,titles);
        store.saveRadarReport(id,report,new RadarRenderer().markdown(report));
    }
    private void stage(String id,String stage) throws Exception { store.updateRadarState(id,"running",stage,null); }
    private void save(String id,Map<String,String> local,String key,String value) throws Exception {
        store.saveRadarCheckpoint(id,key,value); local.put(key,value);
    }
    private void fail(RunSnapshot run,Exception failure) {
        log.warn("Radar run failed: {}",failure.getClass().getSimpleName());
        if(run!=null) try { store.updateRadarState(run.status().runId(),"failed",store.loadRadarRun(run.status().runId()).status().stage(),message(failure)); }
        catch(Exception e) { log.warn("Radar failure state could not be saved"); }
    }
    private String message(Exception e) { return RadarJson.truncate(e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage(),400); }
    @Override public synchronized void close() { closed=true;executor.shutdownNow(); }
}
