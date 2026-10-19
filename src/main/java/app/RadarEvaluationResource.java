package app;

import app.ai.AiConfigStore;
import app.radar.eval.EvaluationService;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RadarEvaluationResource {
    private final EvaluationService service;
    private final FeedStore store;
    private final AiConfigStore ai;
    private final ObjectMapper mapper;
    private final RadarAdminRenderer renderer;
    public RadarEvaluationResource(EvaluationService service, FeedStore store, AiConfigStore ai, ObjectMapper mapper, RadarAdminRenderer renderer) {
        this.service=service;this.store=store;this.ai=ai;this.mapper=mapper;this.renderer=renderer;
    }
    @GetMapping(value="/index/radar/evaluation", produces="text/html;charset=UTF-8")
    public String page() throws Exception {
        var jobs = new ArrayList<Map<String,Object>>();
        if(store!=null) for(var job:store.listRadarJobs("evaluation",30)) jobs.add(Map.of("job",job,"data",mapper.readTree(job.payload())));
        var platforms=ai.settings().profiles().stream().map(p->Map.of("id",p.id(),"name",p.name(),"model",p.config().model())).toList();
        return renderer.render("radar-evaluation",Map.of("jobs",jobs,"platforms",platforms,"samples",EvaluationService.SAMPLES,"requestKey",java.util.UUID.randomUUID().toString()));
    }
    @PostMapping(value="/index/radar/evaluation", produces="text/html;charset=UTF-8")
    public String submit(@RequestParam String platformId,@RequestParam String requestKey) throws Exception {
        service.submit(platformId,requestKey);return page();
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> error() { return ResponseEntity.status(409).body("<p class=\"form-msg err\" role=\"alert\">评测未提交，请确认已启用 AI、平台仍存在且存储可用。</p>"); }
}
