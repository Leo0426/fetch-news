package app.admin;

import app.core.RouteException;
import app.subscriptions.SubscriptionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Controller
@RequestMapping(value = "/index/subscriptions", produces = "text/html;charset=UTF-8")
@ResponseBody
public class SubscriptionResource {
    private final SubscriptionService subscriptions;
    private final TemplateEngine templates;

    public SubscriptionResource(SubscriptionService subscriptions, TemplateEngine templates) {
        this.subscriptions = subscriptions;
        this.templates = templates;
    }

    @GetMapping
    public String page(@RequestParam(required = false) String q, @RequestParam(required = false) String category,
                       @RequestParam(required = false) String filter) {
        Context context = new Context();
        context.setVariable("page", subscriptions.page(q, category, filter));
        return templates.process("index/subscriptions", context);
    }

    @GetMapping("/new")
    public String newRss() {
        return rssForm("", "", null);
    }

    @PostMapping(value = "/create", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> createRss(@RequestParam(defaultValue = "") String name,
                                            @RequestParam(defaultValue = "") String feedUrl) {
        try {
            var source = subscriptions.createRss(name, feedUrl);
            return ResponseEntity.status(201)
                    .header("HX-Retarget", "#subscriptions-view")
                    .header("HX-Reswap", "outerHTML")
                    .header("HX-Trigger-After-Swap", "{\"subscriptionCreated\":{\"cardId\":\"" + source.cardId() + "\"}}")
                    .body(page(source.name(), null, null));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.status(422).body(rssForm(name, feedUrl, error.getMessage()));
        } catch (RouteException error) {
            return ResponseEntity.status(503).body(rssForm(name, feedUrl,
                    "暂时无法保存订阅，请稍后重试；填写的内容已保留。"));
        }
    }

    private String rssForm(String name, String feedUrl, String error) {
        Context context = new Context();
        context.setVariable("name", name);
        context.setVariable("feedUrl", feedUrl);
        context.setVariable("error", error);
        return templates.process("index/subscription-modal", context);
    }

    @GetMapping("/card")
    public String card(@RequestParam String path) {
        Context context = new Context();
        context.setVariable("source", subscriptions.source(path));
        return templates.process("index/subscription-card", context);
    }

    @PostMapping("/start")
    public String start(@RequestParam String path, @RequestParam(required = false) Integer scheduleMinutes) {
        subscriptions.start(path, scheduleMinutes);
        return card(path);
    }

    @PostMapping("/pause")
    public String pause(@RequestParam String path) {
        subscriptions.pause(path);
        return card(path);
    }

    @PostMapping("/refresh")
    public String refresh(@RequestParam String path) {
        subscriptions.refresh(path);
        return card(path);
    }

    @ExceptionHandler({RouteException.class, IllegalArgumentException.class})
    public ResponseEntity<String> invalidRequest(RuntimeException error) {
        int status = error instanceof RouteException route ? route.error().statusCode() : 400;
        return ResponseEntity.status(status).contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(
                "<p class=\"form-msg err\" role=\"alert\">" + HtmlUtils.htmlEscape(error.getMessage()) + "</p>");
    }
}
