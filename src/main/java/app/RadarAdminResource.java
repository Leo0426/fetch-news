package app;

import app.radar.Profile;
import app.radar.ProfileStore;
import app.radar.RadarConfig;
import app.radar.RadarConfigStore;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RadarAdminResource {
    private final ProfileStore profiles;
    private final RadarConfigStore config;
    private final RadarAdminRenderer renderer;
    public RadarAdminResource(ProfileStore profiles, RadarConfigStore config, RadarAdminRenderer renderer) {
        this.profiles = profiles; this.config = config; this.renderer = renderer;
    }
    @GetMapping(value = "/index/radar", produces = "text/html;charset=UTF-8")
    public String settings(@RequestParam(required = false) String profileId) throws IOException {
        var all = profiles.loadAll();
        var selected = all.getOrDefault(profileId, all.values().iterator().next());
        return renderer.render("radar-settings", Map.of("profiles", all, "selected", selected, "config", config.get()));
    }
    @PostMapping(value = "/index/radar/profiles", produces = "text/html;charset=UTF-8")
    public String saveProfile(@RequestParam Map<String, String> form) throws IOException {
        Map<String, String> blocks = new LinkedHashMap<>();
        form.forEach((key, value) -> { if (key.startsWith("block.") && !value.isBlank()) blocks.put(key.substring(6), value); });
        String blockId = form.getOrDefault("newBlockId", "").strip();
        if (!blockId.isEmpty()) {
            if (blocks.containsKey(blockId)) throw new IllegalArgumentException("Block ID already exists");
            blocks.put(blockId, form.getOrDefault("newBlockPrompt", ""));
        }
        Profile profile = new Profile(form.get("id"), form.get("version"), form.get("match"), form.get("scoring"), blocks);
        profiles.save(profile);
        return settings(profile.id());
    }
    @PostMapping(value = "/index/radar/config", produces = "text/html;charset=UTF-8")
    public String saveConfig(@RequestParam Map<String, String> form) throws IOException {
        Map<String, RadarConfig.ProfileSettings> thresholds = new LinkedHashMap<>();
        Map<String, RadarConfig.SourceBinding> sources = new LinkedHashMap<>();
        Map<String, RadarConfig.CategoryGroup> groups = new LinkedHashMap<>();
        var all = profiles.loadAll();
        for (String id : all.keySet()) thresholds.put(id, new RadarConfig.ProfileSettings(
                Double.parseDouble(form.getOrDefault("threshold." + id, "7")), form.containsKey("dedup." + id)));
        form.forEach((key, value) -> {
            if (key.startsWith("source.") && !value.isBlank()) sources.put(key.substring(7),
                    new RadarConfig.SourceBinding(csv(value), form.getOrDefault("category." + key.substring(7), "")));
            if (key.startsWith("group.") && !value.isBlank()) groups.put(key.substring(6), new RadarConfig.CategoryGroup(
                    Integer.parseInt(form.getOrDefault("limit." + key.substring(6), "1")), csv(value)));
        });
        String source = form.getOrDefault("newSource", "").strip();
        if (!source.isEmpty()) sources.put(source, new RadarConfig.SourceBinding(csv(form.get("newProfiles")), form.get("newCategory")));
        String group = form.getOrDefault("newGroup", "").strip();
        if (!group.isEmpty()) groups.put(group, new RadarConfig.CategoryGroup(Integer.parseInt(form.get("newLimit")), csv(form.get("newCategories"))));
        var next = new RadarConfig(form.containsKey("enabled"), form.get("zoneId"), form.get("scheduleCron"), form.get("defaultProfile"),
                number(form, "candidateLimit"), number(form, "maxItems"), number(form, "maxModelCalls"), number(form, "runTimeoutMinutes"),
                thresholds, sources, groups, number(form, "defaultGroupLimit"));
        next.validateProfiles(all.keySet());
        config.save(next);
        return settings(null);
    }
    private static int number(Map<String, String> form, String key) { return Integer.parseInt(form.getOrDefault(key, "0")); }
    private static List<String> csv(String value) { return value == null ? List.of() : Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isBlank()).toList(); }
    @ExceptionHandler({IllegalArgumentException.class, java.time.DateTimeException.class})
    public ResponseEntity<String> invalid() { return ResponseEntity.badRequest().body("<p class=\"form-msg err\" role=\"alert\">配置无效，请检查 Profile、数值、时区、Cron 和分类配额。</p>"); }
    @ExceptionHandler(IOException.class)
    public ResponseEntity<String> unavailable() { return ResponseEntity.status(503).body("<p class=\"form-msg err\" role=\"alert\">配置读取或保存失败，原配置仍保留。</p>"); }
}
