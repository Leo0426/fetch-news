package app.auth;

import app.AppRuntime;
import app.core.RouteConfig;
import app.core.XmlEscaper;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Sign-in, sign-out and the self-service account page with ready-to-use feed URLs.
 */
@Controller
public class AuthResource {
    private static final Logger log = LoggerFactory.getLogger(AuthResource.class);
    private final PasswordLoginLimiter passwordLogins = new PasswordLoginLimiter();

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
    public ResponseEntity<String> loginPage(@RequestParam(defaultValue = "") String next,
                                            @RequestParam(defaultValue = "password") String method,
                                            @RequestParam(defaultValue = "false") boolean changed) {
        if (!auth.enabled()) return redirect(safeNext(next));
        return page(200, loginPage(safeNext(next), "token".equals(method), "", null, changed));
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> login(@RequestParam(required = false) String token,
                                        @RequestParam(defaultValue = "") String username,
                                        @RequestParam(defaultValue = "") String password,
                                        @RequestParam(defaultValue = "") String next,
                                        HttpServletRequest request) {
        String target = safeNext(next);
        if (!auth.enabled()) return redirect(target);
        boolean useToken = token != null;
        String name = username.strip();
        if (!useToken && (name.isEmpty() || name.length() > 40 || password.isEmpty() || password.length() > 256)) {
            return page(401, loginPage(target, false, "", "用户名或密码不正确，或账户已停用。", false));
        }
        String attempt = request.getRemoteAddr() + "/" + name.toLowerCase(Locale.ROOT);
        if (!useToken && !passwordLogins.enter(attempt)) {
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, "60")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(loginPage(target, false, name, "登录尝试较多，请稍后再试。", false));
        }
        AuthService.IssuedSession session = null;
        try {
            session = useToken ? auth.loginWithToken(token) : auth.loginWithPassword(name, password);
        } catch (SQLException e) {
            log.warn("Login unavailable ({})", e.getClass().getSimpleName());
            return page(503, loginPage(target, useToken, name, "认证服务暂时不可用，请稍后重试。", false));
        } finally {
            if (!useToken) passwordLogins.finish(attempt, session != null);
        }
        if (session == null) {
            String message = useToken ? "令牌无效或账户已停用，请检查后重试。" : "用户名或密码不正确，或账户已停用。";
            return page(401, loginPage(target, useToken, name, message, false));
        }
        if (!session.user().admin() && target.startsWith("/index")) target = "/reader";
        return ResponseEntity.status(303).header(HttpHeaders.LOCATION, target)
                .header(HttpHeaders.SET_COOKIE, sessionCookie(request, session))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) throws SQLException {
        auth.logout(AuthFilter.cookieValue(request));
        return ResponseEntity.status(303).header(HttpHeaders.LOCATION, "/login")
                .header(HttpHeaders.SET_COOKIE, AuthFilter.cookieHeader(request, "", 0))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static String sessionCookie(HttpServletRequest request, AuthService.IssuedSession session) {
        return AuthFilter.cookieHeader(request, session.secret(), AuthService.SESSION_LIFETIME.toSeconds());
    }

    @GetMapping(value = "/account", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> account() throws SQLException {
        return page(200, accountPage(null, null, null, null));
    }

    /** Rotates the caller's feed key, invalidating feed URLs already given to RSS readers. */
    @PostMapping(value = "/account/feed-key", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> resetFeedKey() throws SQLException {
        requireAuthEnabled();
        auth.resetFeedKey(CurrentUser.id());
        return page(200, accountPage("rss", "订阅密钥已更新，请在 RSS 阅读器中替换为新的订阅地址。", null, null));
    }

    @PostMapping(value = "/account/tokens", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> createToken(@RequestParam String name) throws SQLException {
        requireAuthEnabled();
        try {
            IssuedToken issued = auth.createToken(CurrentUser.id(), name);
            return page(200, accountPage("tokens", null, issued.token(), null));
        } catch (IllegalArgumentException e) {
            return page(422, accountPage("tokens", null, null, e.getMessage()));
        }
    }

    @PostMapping(value = "/account/tokens/{id}/revoke", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> revokeToken(@PathVariable long id) throws SQLException {
        requireAuthEnabled();
        try {
            auth.revokeToken(CurrentUser.id(), id);
            return redirect("/account#access-tokens");
        } catch (IllegalArgumentException e) {
            return page(422, accountPage("tokens", null, null, e.getMessage()));
        }
    }

    @PostMapping(value = "/account/password", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> changePassword(@RequestParam(defaultValue = "") String oldPassword,
                                                @RequestParam String newPassword,
                                                HttpServletRequest request) throws SQLException {
        requireAuthEnabled();
        try {
            auth.changePassword(CurrentUser.id(), oldPassword, newPassword);
        } catch (IllegalArgumentException e) {
            return page(422, accountPage("password", null, null, e.getMessage()));
        }
        return ResponseEntity.status(303).header(HttpHeaders.LOCATION, "/login?changed=true&next=/account")
                .header(HttpHeaders.SET_COOKIE, AuthFilter.cookieHeader(request, "", 0))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    /** Older clients rotate the account's primary token through this endpoint. */
    @PostMapping(value = "/account/token", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> resetToken(HttpServletRequest request) throws SQLException {
        requireAuthEnabled();
        try {
            IssuedToken issued = auth.resetToken(CurrentUser.id());
            AuthService.IssuedSession session = auth.loginWithToken(issued.token());
            var response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
            if (session != null) response.header(HttpHeaders.SET_COOKIE, sessionCookie(request, session));
            return response.body(accountPage("tokens", null, issued.token(), null));
        } catch (IllegalArgumentException e) {
            return page(422, accountPage("tokens", null, null, e.getMessage()));
        }
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

    private String loginPage(String next, boolean tokenMode, String username, String error, boolean changed) {
        Context ctx = new Context();
        ctx.setVariable("next", next);
        ctx.setVariable("tokenMode", tokenMode);
        ctx.setVariable("username", username);
        ctx.setVariable("changed", changed);
        ctx.setVariable("error", error);
        return engine.process("auth/login", ctx);
    }

    private String accountPage(String section, String message, String newToken, String error) throws SQLException {
        UserAccount viewer = CurrentUser.get();
        UserAccount user = auth.enabled() ? auth.findUser(viewer.id()) : viewer;
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账户已不存在。");
        Context ctx = new Context();
        ctx.setVariable("user", user);
        ctx.setVariable("viewer", viewer);
        ctx.setVariable("authEnabled", auth.enabled());
        ctx.setVariable("feedKey", user.feedKey());
        ctx.setVariable("feedKeyParam", AuthFilter.FEED_KEY_PARAM);
        ctx.setVariable("feeds", feedLinks());
        ctx.setVariable("feedbackSection", section);
        ctx.setVariable("message", message);
        ctx.setVariable("newToken", newToken);
        ctx.setVariable("error", error);
        ctx.setVariable("tokens", auth.enabled() ? auth.listTokens(viewer.id()) : List.of());
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
        if (!auth.enabled()) throw new ResponseStatusException(
                HttpStatus.CONFLICT, "认证未启用");
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

    private static ResponseEntity<String> page(int status, String html) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(html);
    }

    private static ResponseEntity<String> redirect(String target) {
        return ResponseEntity.status(303).header(HttpHeaders.LOCATION, target).build();
    }
}
