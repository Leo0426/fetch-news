package app.admin;

import app.AppRuntime;
import app.core.CredentialCheck;
import app.core.CredentialConfig;
import app.core.RouteConfig;
import app.fetch.FetchScheduler;
import app.routes.bilibili.BilibiliCookieVerifier;
import app.routes.twitter.TwitterCookieVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * The credentials page under /index#credentials: saving cookies, checking them against their services,
 * and refetching the subscribed sources that depend on a changed cookie.
 */
@Controller
@RequestMapping(value = "/index/credentials", produces = "text/html;charset=UTF-8")
@ResponseBody
public class CredentialsResource {
    private static final List<String> TWITTER_SOURCES = List.of("/x/", "/twitter/");
    private static final List<String> BILIBILI_SOURCES = List.of("/bilibili/");

    private final AppRuntime runtime;
    private final FetchScheduler scheduler;
    private final TemplateEngine templates;
    private final TwitterCookieVerifier twitterVerifier;
    private final BilibiliCookieVerifier bilibiliVerifier;

    @Autowired
    public CredentialsResource(AppRuntime runtime, FetchScheduler scheduler, TemplateEngine templates,
                               ObjectMapper objectMapper) {
        this(runtime, scheduler, templates,
                new TwitterCookieVerifier(runtime.fetchClient(), objectMapper, runtime.cacheService()),
                new BilibiliCookieVerifier(runtime.fetchClient(), objectMapper));
    }

    CredentialsResource(AppRuntime runtime, FetchScheduler scheduler, TemplateEngine templates,
                        TwitterCookieVerifier twitterVerifier, BilibiliCookieVerifier bilibiliVerifier) {
        this.runtime = runtime;
        this.scheduler = scheduler;
        this.templates = templates;
        this.twitterVerifier = twitterVerifier;
        this.bilibiliVerifier = bilibiliVerifier;
    }

    @GetMapping
    public String page() {
        return page(runtime.credentialConfigStore().getStored(), null);
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String save(@RequestParam(defaultValue = "") String bilibiliCookie,
                       @RequestParam(defaultValue = "") String twitterCookie,
                       @RequestParam(required = false) List<String> uidKey,
                       @RequestParam(required = false) List<String> uidCookie,
                       @RequestParam(required = false) List<String> uidName) {
        CredentialConfig previous = runtime.credentialConfigStore().getStored();
        CredentialConfig saved = runtime.credentialConfigStore().save(
                form(bilibiliCookie, twitterCookie, uidKey, uidCookie, uidName));
        int refetched = 0;
        if (!saved.twitterCookie().isBlank() && !saved.twitterCookie().equals(previous.twitterCookie())) {
            refetched += refetch(TWITTER_SOURCES);
        }
        if (!saved.bilibiliCookie().equals(previous.bilibiliCookie())
                || !saved.bilibiliUidCookies().equals(previous.bilibiliUidCookies())) {
            refetched += refetch(BILIBILI_SOURCES);
        }
        return page(saved, refetched == 0 ? "已保存" : "已保存，正在重新抓取 " + refetched + " 个使用该凭据的订阅");
    }

    /** Checks the cookies currently in the form without saving them. */
    @PostMapping(value = "/verify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String verify(@RequestParam(defaultValue = "") String bilibiliCookie,
                         @RequestParam(defaultValue = "") String twitterCookie,
                         @RequestParam(required = false) List<String> uidKey,
                         @RequestParam(required = false) List<String> uidCookie,
                         @RequestParam(required = false) List<String> uidName) {
        CredentialConfig config = form(bilibiliCookie, twitterCookie, uidKey, uidCookie, uidName);
        List<CredentialCheck> checks = new ArrayList<>();
        if (!config.twitterCookie().isBlank()) checks.add(twitterVerifier.verify(config.twitterCookie()));
        if (!config.bilibiliCookie().isBlank()) checks.add(bilibiliVerifier.verifyPublic(config.bilibiliCookie()));
        config.bilibiliUidCookies().forEach((uid, cookie) ->
                checks.add(bilibiliVerifier.verifyLogin(uid, config.bilibiliUidNames().get(uid), cookie)));
        Context context = new Context();
        context.setVariable("checks", checks);
        return templates.process("index/credential-checks", context);
    }

    /** Saves a single per-UID Bilibili cookie from the route modal. */
    @PostMapping(value = "/uid-cookie", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String saveUidCookie(@RequestParam String uid, @RequestParam(defaultValue = "") String cookie) {
        runtime.credentialConfigStore().putUidCookie(uid.strip(), cookie.strip());
        refetch(BILIBILI_SOURCES);
        return "<span class='form-msg ok'>Cookie 已保存</span>";
    }

    private String page(CredentialConfig config, String message) {
        Context context = new Context();
        context.setVariable("config", config);
        context.setVariable("message", message);
        return templates.process("index/credentials", context);
    }

    /** Fetches the enabled sources served by the given route prefixes now, so their status reflects the new cookie. */
    private int refetch(List<String> prefixes) {
        int count = 0;
        for (RouteConfig config : runtime.routeConfigStore().list(runtime.routeRegistry().paths())) {
            if (config.enabled() && !runtime.isTemplate(config)
                    && prefixes.stream().anyMatch(config.sourcePath()::startsWith)) {
                scheduler.triggerFetch(config.path());
                count++;
            }
        }
        return count;
    }

    private static CredentialConfig form(String bilibiliCookie, String twitterCookie, List<String> uidKey,
                                         List<String> uidCookie, List<String> uidName) {
        Map<String, String> cookies = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        if (uidKey != null && uidCookie != null) {
            int len = Math.min(uidKey.size(), uidCookie.size());
            for (int i = 0; i < len; i++) {
                String uid = uidKey.get(i).strip();
                String cookie = uidCookie.get(i).strip();
                if (uid.isBlank() || cookie.isBlank()) continue;
                cookies.put(uid, cookie);
                if (uidName != null && i < uidName.size() && !uidName.get(i).isBlank()) {
                    names.put(uid, uidName.get(i).strip());
                }
            }
        }
        return new CredentialConfig(bilibiliCookie.strip(), cookies, names, twitterCookie.strip());
    }
}
