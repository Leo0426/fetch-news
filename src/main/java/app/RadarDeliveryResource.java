package app;

import app.feishu.RadarDeliveryService;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RadarDeliveryResource {
    private final RadarDeliveryService service;
    private final FeedStore store;
    private final ObjectMapper mapper;
    private final RadarAdminRenderer renderer;
    public RadarDeliveryResource(RadarDeliveryService service,FeedStore store,ObjectMapper mapper,RadarAdminRenderer renderer) {
        this.service=service;this.store=store;this.mapper=mapper;this.renderer=renderer;
    }
    @GetMapping(value="/index/radar/delivery",produces="text/html;charset=UTF-8")
    public String page() throws Exception {
        var jobs=new ArrayList<Map<String,Object>>();
        if(store!=null) for(var job:store.listRadarJobs("delivery",50)) jobs.add(Map.of("job",job,"data",mapper.readTree(job.payload())));
        var reports=store==null ? java.util.List.of() : store.listRadarRuns(100).stream().filter(run->java.util.Set.of("success","partial").contains(run.status().state())).toList();
        boolean enabled=store!=null && mapper.readTree(service.policy().payload()).path("enabled").asBoolean();
        return renderer.render("radar-delivery",Map.of("jobs",jobs,"reports",reports,"enabled",enabled));
    }
    @PostMapping(value="/index/radar/delivery",produces="text/html;charset=UTF-8")
    public String submit(@RequestParam String runId) throws Exception { service.submit(runId);return page(); }
    @PostMapping(value="/index/radar/delivery/policy",produces="text/html;charset=UTF-8")
    public String policy(@RequestParam(defaultValue="false") boolean enabled) throws Exception { service.savePolicy(enabled);return page(); }
    @PostMapping(value="/index/radar/delivery/{id}/retry",produces="text/html;charset=UTF-8")
    public String retry(@PathVariable String id,@RequestParam(defaultValue="false") boolean confirmUncertain) throws Exception { service.retry(id,confirmUncertain);return page(); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> error() { return ResponseEntity.status(409).body("<p class=\"form-msg err\" role=\"alert\">投递操作失败。请检查飞书总开关、默认 Webhook、日报状态；恢复投递需使用原目标，未知回执需确认可能重复。</p>"); }
}
