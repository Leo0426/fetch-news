package app;

import app.radar.RadarConfigStore;
import app.radar.RadarService;
import app.radar.RadarTypes.RunStatus;
import app.reader.RadarPageView;
import app.store.FeedStore;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RadarResource {
    private final RadarService service;
    private final FeedStore store;
    private final RadarConfigStore config;
    private final RadarTemplateRenderer renderer;
    public RadarResource(RadarService service,FeedStore store,RadarConfigStore config,RadarTemplateRenderer renderer) {
        this.service=service;this.store=store;this.config=config;this.renderer=renderer;
    }
    @GetMapping(value="/reader/radar",produces="text/html;charset=UTF-8")
    public String page(@RequestParam(required=false) String date,@RequestParam(required=false) String zoneId) throws SQLException {
        available();
        String zone=zoneId==null ? config.get().zoneId() : ZoneId.of(zoneId).getId();
        String day=date==null ? LocalDate.now(ZoneId.of(zone)).toString() : LocalDate.parse(date).toString();
        var latest=store.latestRadarRun(day,zone,false);
        var saved=store.latestRadarRun(day,zone,true);
        var report=saved==null ? null : store.loadRadarReport(saved.status().runId());
        Map<String,String> titles=new LinkedHashMap<>();
        if(saved!=null) for(var candidate:saved.candidates()) titles.put(candidate.link(),candidate.title());
        return renderer.render(new RadarPageView(day,zone,config.get().enabled(),java.util.UUID.randomUUID().toString(),
                report,latest==null ? null : latest.status(),saved==null ? null : saved.status(),titles));
    }
    @PostMapping("/reader/radar/runs")
    public ResponseEntity<RunStatus> submit(@RequestParam String date,@RequestParam(required=false) String zoneId,@RequestParam String requestKey) throws Exception {
        available();
        return ResponseEntity.accepted().body(service.submit(date,zoneId==null ? config.get().zoneId() : zoneId,requestKey));
    }
    @GetMapping("/reader/radar/runs/{runId}")
    public RunStatus status(@PathVariable String runId) throws SQLException { available();return service.status(runId); }
    @PostMapping("/reader/radar/runs/{runId}/resume")
    public ResponseEntity<RunStatus> resume(@PathVariable String runId) throws SQLException { available();return ResponseEntity.accepted().body(service.resume(runId)); }
    @GetMapping(value="/reader/radar/runs/{runId}/markdown",produces="text/markdown;charset=UTF-8")
    public ResponseEntity<String> markdown(@PathVariable String runId) throws SQLException {
        available();
        var status=service.status(runId);
        String text=store.loadRadarMarkdown(runId);
        if(text==null) throw new NoSuchElementException("Report not available");
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=radar-"+status.date()+"-v"+status.version()+".md").body(text);
    }
    private void available() throws SQLException { if(store==null) throw new SQLException("Article storage unavailable"); }
    @ExceptionHandler({IllegalArgumentException.class,DateTimeException.class})
    public ResponseEntity<Map<String,String>> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error","Invalid date, time zone or request")); }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String,String>> missing(Exception e) { return ResponseEntity.status(404).body(Map.of("error","Radar run or report not found")); }
    @ExceptionHandler({SQLException.class,IOException.class})
    public ResponseEntity<Map<String,String>> unavailable(Exception e) { return ResponseEntity.status(503).body(Map.of("error","Radar storage or configuration unavailable")); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,String>> conflict(IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error",e.getMessage())); }
}
