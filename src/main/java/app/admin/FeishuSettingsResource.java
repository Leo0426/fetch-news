package app.admin;

import app.AppRuntime;
import app.feishu.FeishuCategoryConfig;
import app.feishu.FeishuConfig;
import app.feishu.FeishuConfigStore;
import app.feishu.FeishuPusher;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Feishu push settings in /index: the default webhook, per-topic rules and a connectivity test.
 */
@Controller
@RequestMapping("/index")
public class FeishuSettingsResource {
    private final AppRuntime runtime;
    private final AdminTemplateRenderer templates;
    private final FeishuConfigStore feishuConfigStore;
    private final FeishuPusher feishuPusher;

    public FeishuSettingsResource(AppRuntime runtime, AdminTemplateRenderer templates, FeishuConfigStore feishuConfigStore,
                                  FeishuPusher feishuPusher) {
        this.runtime = runtime;
        this.templates = templates;
        this.feishuConfigStore = feishuConfigStore;
        this.feishuPusher = feishuPusher;
    }

    /**
     * Feishu push settings page fragment.
     */
    @GetMapping(value = "/feishu", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String feishuSettings() {
        return templates.feishuSettings(feishuConfigStore.get(), allCategories(), null);
    }

    /**
     * Save global Feishu config (webhook URL, enabled flag, default max items).
     * Preserves existing per-category configs.
     */
    @PostMapping(
            value = "/feishu",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String saveFeishuConfig(
            @RequestParam(defaultValue = "") String webhookUrl,
            @RequestParam(required = false) String enabled,
            @RequestParam(defaultValue = "10") int maxItemsPerPush) {
        FeishuConfig current = feishuConfigStore.get();
        FeishuConfig config = new FeishuConfig(
                webhookUrl.strip(),
                "on".equals(enabled),
                maxItemsPerPush,
                current.categories());
        feishuConfigStore.save(config);
        return templates.feishuSettings(config, allCategories(), "已保存");
    }

    /**
     * Save config for a single category. Merges into the existing categories map.
     */
    @PostMapping(
            value = "/feishu/category",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String saveFeishuCategory(
            @RequestParam String categoryName,
            @RequestParam(required = false) String enabled,
            @RequestParam(defaultValue = "immediate") String mode,
            @RequestParam(defaultValue = "") String webhookUrl,
            @RequestParam(defaultValue = "") String digestCron,
            @RequestParam(defaultValue = "0") int maxItemsPerPush) {
        FeishuConfig current = feishuConfigStore.get();
        var categories = new LinkedHashMap<>(current.categories());
        categories.put(categoryName, new FeishuCategoryConfig(
                "on".equals(enabled),
                webhookUrl.strip(),
                mode,
                digestCron.strip(),
                maxItemsPerPush));
        feishuConfigStore.save(new FeishuConfig(
                current.webhookUrl(), current.enabled(), current.maxItemsPerPush(), categories));
        return "<span class='form-msg ok'>已保存</span>";
    }

    /**
     * Tests Feishu webhook connectivity — returns an inline status fragment.
     */
    @PostMapping(
            value = "/feishu/test",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String testFeishu(@RequestParam(defaultValue = "") String webhookUrl) {
        if (webhookUrl.isBlank()) {
            return "<span class='form-msg err'>请先填写 Webhook URL</span>";
        }
        boolean ok = feishuPusher.test(webhookUrl.strip());
        return ok
                ? "<span class='form-msg ok'>连接成功，请查收飞书测试消息</span>"
                : "<span class='form-msg err'>连接失败，请检查 Webhook URL 是否正确</span>";
    }

    private List<String> allCategories() {
        return runtime.topics();
    }

}
