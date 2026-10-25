package app.reader;

import app.radar.CommunityContextService;
import app.store.FeedStore;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RadarCommunityResource {
    private final CommunityContextService service;
    private final FeedStore store;

    public RadarCommunityResource(CommunityContextService service, FeedStore store) {
        this.service = service;
        this.store = store;
    }

    @GetMapping("/reader/radar/context")
    public ResponseEntity<?> cached(@RequestParam String link) throws Exception {
        var context = service.cached(link);
        return context == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(context);
    }

    @PostMapping("/reader/radar/context")
    public ResponseEntity<?> submit(
            @RequestParam String runId, @RequestParam String link, @RequestParam(required = false) String discussionUrl)
            throws Exception {
        var job = service.submit(runId, link, discussionUrl);
        return ResponseEntity.accepted().body(Map.of("id", job.id(), "state", job.state()));
    }

    @GetMapping("/reader/radar/context/jobs/{id}")
    public ResponseEntity<?> status(@PathVariable String id) throws Exception {
        var job = store == null ? null : store.radarJobs().loadJob(id);
        if (job == null || !job.kind().equals("community"))
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("id", job.id(), "state", job.state()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> error(Exception failure) {
        return ResponseEntity.status(failure instanceof IllegalArgumentException ? 400 : 503)
                .body(Map.of("error", "无法获取讨论。请提供引用这篇原文的 Hacker News 讨论链接；检查网络与存储后重试。"));
    }
}
