package app.auth;

import app.AppRuntime;
import app.core.RouteConfig;
import app.core.XmlEscaper;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Sign-in, sign-out and the self-service account page with ready-to-use feed URLs.
 */
@Controller
public class AuthResource {
    private static final Logger log = LoggerFactory.getLogger(AuthResource.class);
    private static final long COOKIE_MAX_AGE = Duration.ofDays(180).toSeconds();

    private final AuthService auth;
    private final AppRuntime runtime;
    private final TemplateEngine engine;

    public AuthResource(AuthService auth, AppRuntime runtime, TemplateEngine engine) {
        this.auth = auth;
        this.runtime = runtime;
        this.engine = engine;
    }

    public record FeedLink(String name, String path) {}

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> loginPage(@RequestParam(defaultValue = "") String next) {
        if (!auth.enabled()) return redirect(safeNext(next));
        return page(200, loginPage(safeNext(next), null));
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> login(@RequestParam(defaultValue = "") String token,
                                        @RequestParam(defaultValue = "") String next,
                                        HttpServletRequest request) {
        String target = safeNext(next);
        if (!auth.enabled()) return redirect(target);
        UserAccount user;
        try {
            user = auth.authenticateToken(token);
        } catch (SQLException e) {
            log.warn("login lookup failed: {}", e.getMessage());
            return page(503, loginPage(target, "认证服务暂时不可用，请稍后重试。"));
        }
        if (user == null) {
            log.info("rejected sign-in attempt from {}", clientAddress(request));
            return page(401, loginPage(target, "令牌无效或账户已停用，请检查后重试。"));
        }
        if (!user.admin() && target.startsWith("/index")) target = "/reader";
        return ResponseEntity.status(303)
                .header(HttpHeaders.LOCATION, target)
                .header(HttpHeaders.SET_COOKIE, AuthFilter.cookieHeader(request, token.strip(), COOKIE_MAX_AGE))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        return ResponseEntity.status(303)
                .header(HttpHeaders.LOCATION, "/login")
                .header(HttpHeaders.SET_COOKIE, AuthFilter.cookieHeader(request, "", 0))
                .build();
    }

    @GetMapping(value = "/account", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> account() throws SQLException {
        return page(200, accountPage(null, null));
    }

    /** Rotates the caller's feed key, invalidating feed URLs already given to RSS readers. */
    @PostMapping(value = "/account/feed-key", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> resetFeedKey() throws SQLException {
        requireAuthEnabled();
        auth.resetFeedKey(CurrentUser.id());
        return page(200, accountPage("订阅密钥已更新，请在 RSS 阅读器中替换为新的订阅地址。", null));
    }

    /** Rotates the caller's login token and keeps this browser signed in with the new one. */
    @PostMapping(value = "/account/token", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> resetToken(HttpServletRequest request) throws SQLException {
        requireAuthEnabled();
        IssuedToken issued = auth.resetToken(CurrentUser.id());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, AuthFilter.cookieHeader(request, issued.token(), COOKIE_MAX_AGE))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(accountPage("登录令牌已重置，其他设备需要使用新令牌重新登录。", issued.token()));
    }

    /** OPML export of every enabled feed, authenticated with the caller's feed key. */
    @GetMapping(value = "/account/opml", produces = "text/x-opml;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> opml(HttpServletRequest request) throws SQLException {
        String base = baseUrl(request);
        String key = feedKey();
        StringBuilder body = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <opml version="2.0">
                  <head><title>fetch-news</title></head>
                  <body>
                """);
        for (FeedLink link : feedLinks()) {
            String url = base + link.path() + (key == null ? "" : "?" + AuthFilter.FEED_KEY_PARAM + "=" + key);
            body.append("    <outline type=\"rss\" text=\"").append(XmlEscaper.escape(link.name()))
                    .append("\" title=\"").append(XmlEscaper.escape(link.name()))
                    .append("\" xmlUrl=\"").append(XmlEscaper.escape(url)).append("\"/>\n");
        }
        body.append("  </body>\n</opml>\n");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fetch-news.opml\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body.toString());
    }

    private String loginPage(String next, String error) {
        Context ctx = new Context();
        ctx.setVariable("next", next);
        ctx.setVariable("error", error);
        return engine.process("auth/login", ctx);
    }

    private String accountPage(String message, String newToken) throws SQLException {
        UserAccount viewer = CurrentUser.get();
        UserAccount user = auth.enabled() ? auth.findUser(viewer.id()) : viewer;
        Context ctx = new Context();
        ctx.setVariable("user", user);
        ctx.setVariable("viewer", viewer);
        ctx.setVariable("authEnabled", auth.enabled());
        ctx.setVariable("feedKey", user == null ? null : user.feedKey());
        ctx.setVariable("feedKeyParam", AuthFilter.FEED_KEY_PARAM);
        ctx.setVariable("feeds", feedLinks());
        ctx.setVariable("message", message);
        ctx.setVariable("newToken", newToken);
        return engine.process("auth/account", ctx);
    }

    private String feedKey() throws SQLException {
        if (!auth.enabled()) return null;
        UserAccount user = auth.findUser(CurrentUser.id());
        return user == null ? null : user.feedKey();
    }

    private List<FeedLink> feedLinks() {
        return runtime.routeConfigStore().list(runtime.routeRegistry().paths()).stream()
                .filter(RouteConfig::enabled)
                .filter(config -> !config.path().contains(":"))
                .map(config -> new FeedLink(
                        config.alias() == null || config.alias().isBlank() ? config.path() : config.alias(),
                        config.path()))
                .toList();
    }

    private void requireAuthEnabled() {
        if (!auth.enabled()) throw new IllegalStateException("authentication is disabled");
    }

    /** Only same-site relative paths are accepted, so the login form cannot redirect elsewhere. */
    static String safeNext(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.contains("\\")
                || next.startsWith("/login") || next.chars().anyMatch(Character::isISOControl)) {
            return "/reader";
        }
        return next;
    }

    private static String baseUrl(HttpServletRequest request) {
        String proto = firstHeaderValue(request, "X-Forwarded-Proto");
        String host = firstHeaderValue(request, "X-Forwarded-Host");
        if (proto == null) proto = request.getScheme();
        if (host == null) host = firstHeaderValue(request, "Host");
        if (host == null) host = request.getServerName() + ":" + request.getServerPort();
        return proto + "://" + host;
    }

    private static String firstHeaderValue(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) return null;
        return value.split(",")[0].strip();
    }

    private static String clientAddress(HttpServletRequest request) {
        String forwarded = firstHeaderValue(request, "X-Forwarded-For");
        return forwarded != null ? forwarded : request.getRemoteAddr();
    }

    private static ResponseEntity<String> page(int status, String html) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(html);
    }

    private static ResponseEntity<String> redirect(String target) {
        return ResponseEntity.status(303).header(HttpHeaders.LOCATION, target).build();
    }
}
