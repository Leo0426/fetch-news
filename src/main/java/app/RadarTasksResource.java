package app;

import app.radar.RadarService;
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
public class RadarTasksResource {
    private final FeedStore store;
    private final RadarService service;
    private final RadarAdminRenderer renderer;
    public RadarTasksResource(FeedStore store, RadarService service, RadarAdminRenderer renderer) {
        this.store = store; this.service = service; this.renderer = renderer;
    }
    @GetMapping(value = "/index/radar/tasks", produces = "text/html;charset=UTF-8")
    public String tasks() throws Exception {
        return renderer.render("radar-tasks", Map.of("runs", store == null ? java.util.List.of() : store.listRadarRuns(50), "requestKey", java.util.UUID.randomUUID().toString()));
    }
    @PostMapping(value = "/index/radar/tasks/{id}/resume", produces = "text/html;charset=UTF-8")
    public String resume(@PathVariable String id) throws Exception { service.resume(id); return tasks(); }
    @PostMapping(value = "/index/radar/tasks/{id}/retry", produces = "text/html;charset=UTF-8")
    public String retry(@PathVariable String id, @RequestParam String requestKey) throws Exception { service.retry(id, requestKey); return tasks(); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> error(Exception failure) {
        return ResponseEntity.status(failure instanceof IllegalStateException ? 409 : 503)
                .body("<p class=\"form-msg err\" role=\"alert\">操作失败，请检查雷达、AI 和存储状态后刷新任务列表。重跑会按当前配置创建新版本。</p>");
    }
}
