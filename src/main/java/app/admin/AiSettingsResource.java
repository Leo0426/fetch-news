package app.admin;

import app.ai.AiConfig;
import app.ai.AiConfigStore;
import app.ai.AiModelList;
import app.ai.AiProviderForm;
import app.ai.AiSettingsPage;
import app.ai.OllamaClient;
import app.store.FeedStore;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/**
 * AI platform settings in /index: saved platforms, the global switch, connection tests and model discovery.
 */
@Controller
@RequestMapping("/index")
public class AiSettingsResource {
    private final AdminTemplateRenderer templates;
    private final FeedStore feedStore;
    private final AiConfigStore aiConfigStore;
    private final OllamaClient ollamaClient;

    public AiSettingsResource(AdminTemplateRenderer templates, FeedStore feedStore, AiConfigStore aiConfigStore,
                              OllamaClient ollamaClient) {
        this.templates = templates;
        this.feedStore = feedStore;
        this.aiConfigStore = aiConfigStore;
        this.ollamaClient = ollamaClient;
    }

    /**
     * AI settings page fragment.
     */
    @GetMapping(value = "/ai", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> aiSettings(@RequestParam(required = false) String profileId) {
        try { return ResponseEntity.ok(renderAiSettings(profileId, null)); }
        catch (NoSuchElementException e) { return ResponseEntity.status(404).body("AI platform not found"); }
    }

    @PostMapping(value = "/ai", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> saveAiConfig(
            @ModelAttribute AiProviderForm form,
            @RequestParam(required = false) List<String> modelIds,
            @RequestParam(defaultValue = "false") boolean activate) {
        try {
            String id=aiConfigStore.saveProfile(form.profileId(),form.name(),form.resolve(aiConfigStore),activate,modelIds);
            return ResponseEntity.ok(renderAiSettings(id,activate ? "已保存并启用此提供商" : "已保存提供商配置"));
        } catch(IllegalArgumentException | NoSuchElementException e) { return aiFormError(e.getMessage(),400); }
        catch(UncheckedIOException e) { return aiFormError("保存失败，原配置未改变",500); }
    }

    @PostMapping(value = "/ai/activate", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> activateAi(@RequestParam String profileId) {
        try { aiConfigStore.activate(profileId); return ResponseEntity.ok(renderAiSettings(profileId,"已切换当前平台")); }
        catch(IllegalArgumentException | NoSuchElementException e) { return aiFormError("平台不存在",400); }
        catch(UncheckedIOException e) { return aiFormError("保存失败，原配置未改变",500); }
    }

    @PostMapping(value = "/ai/enabled", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> enableAi(@RequestParam(defaultValue = "false") boolean enabled) {
        try {
            aiConfigStore.setEnabled(enabled);
            return ResponseEntity.ok(renderAiSettings(null, enabled ? "已开启 AI" : "已关闭 AI"));
        } catch (UncheckedIOException e) { return aiFormError("保存失败，原配置未改变", 500); }
    }

    @PostMapping(value = "/ai/delete", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> deleteAi(@RequestParam String profileId) {
        try { aiConfigStore.deleteProfile(profileId); return ResponseEntity.ok(renderAiSettings(null,"已删除平台配置")); }
        catch(IllegalArgumentException | NoSuchElementException e) { return aiFormError("请先切换到其他平台，再删除此配置",400); }
        catch(UncheckedIOException e) { return aiFormError("保存失败，原配置未改变",500); }
    }

    private String renderAiSettings(String selectedId,String message) {
        int[] counts=aiCounts();
        return templates.aiSettings(AiSettingsPage.from(aiConfigStore,selectedId),counts[0],counts[1],message);
    }

    private ResponseEntity<String> aiFormError(String message,int status) {
        return ResponseEntity.status(status).body("<div class=\"form-msg err\">"+HtmlUtils.htmlEscape(message)+"</div>");
    }

    private int[] aiCounts() {
        int extracts = 0, summaries = 0;
        if (feedStore != null) {
            try {
                extracts = feedStore.aiCache().countExtracts();
            } catch (Exception ignored) {
            }
            try {
                summaries = feedStore.aiCache().countSummaries();
            } catch (Exception ignored) {
            }
        }
        return new int[]{extracts, summaries};
    }

    /**
     * Test the active AI platform connectivity — returns an inline status fragment.
     */
    @GetMapping(value = "/ai/test", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String testAi() {
        return templates.aiTestResult(ollamaClient.checkConnection(), aiConfigStore.get().model());
    }

    @PostMapping(value = "/ai/test", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> testAiProvider(@ModelAttribute AiProviderForm form) {
        try {
            AiConfig config = form.resolve(aiConfigStore);
            return ResponseEntity.ok(templates.aiTestResult(ollamaClient.checkConnection(config), config.model()));
        } catch (IllegalArgumentException | NoSuchElementException e) { return aiFormError(e.getMessage(), 400); }
    }

    @PostMapping(value = "/ai/models", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<AiModelList> aiModels(@ModelAttribute AiProviderForm form) {
        try {
            return ResponseEntity.ok(AiModelList.from(ollamaClient.checkConnection(form.resolveForDiscovery(aiConfigStore))));
        } catch (IllegalArgumentException | NoSuchElementException e) {
            return ResponseEntity.badRequest().body(new AiModelList(List.of(), e.getMessage()));
        }
    }

}
