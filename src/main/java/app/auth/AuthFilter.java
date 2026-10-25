package app.auth;

import app.FrontendAssetResource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * Gate in front of every endpoint.
 *
 * <ul>
 *   <li>Browser pages authenticate with the HttpOnly login cookie set by {@code /login}.</li>
 *   <li>API clients may send {@code Authorization: Bearer <token>}.</li>
 *   <li>RSS readers may use {@code ?key=<feed key>} or HTTP Basic with the token or feed key as
 *       password. Feed keys never unlock the reader or admin UI.</li>
 *   <li>{@code /index} is admin-only; members can read, bookmark and mark items read.</li>
 * </ul>
 */
public class AuthFilter extends OncePerRequestFilter {
    public static final String COOKIE = "fn_auth";
    public static final String FEED_KEY_PARAM = "key";
    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    enum Access { PUBLIC, FEED, READER, ADMIN }

    private final AuthService auth;

    public AuthFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        if (!auth.enabled()) {
            request.setAttribute(CurrentUser.ATTRIBUTE, UserAccount.owner());
            chain.doFilter(request, response);
            return;
        }
        String path = canonicalPath(request);
        if (path == null) {
            response.sendError(400, "请求路径无效");
            return;
        }
        Access access = classify(path, request.getMethod());
        if (access == Access.PUBLIC) {
            chain.doFilter(request, response);
            return;
        }

        Authenticated authenticated;
        try {
            authenticated = authenticate(request, access == Access.FEED);
        } catch (SQLException e) {
            log.warn("authentication lookup failed: {}", e.getMessage());
            response.sendError(503, "认证服务暂时不可用");
            return;
        }
        if (authenticated == null) {
            if (hasCookie(request)) clearCookie(request, response);
            unauthenticated(request, response, path, access);
            return;
        }
        UserAccount user = authenticated.user();
        if (authenticated.viaCookie() && !isSafe(request.getMethod()) && !sameOrigin(request)) {
            response.sendError(403, "跨站请求已拒绝");
            return;
        }
        if (access == Access.ADMIN && !user.admin()) {
            if ("GET".equals(request.getMethod()) && ("/index".equals(path) || "/index/".equals(path))
                    && request.getHeader("HX-Request") == null) {
                redirect(response, "/reader");
            } else {
                response.sendError(403, "需要管理员权限");
            }
            return;
        }
        if ("/".equals(path) && !user.admin()) {
            redirect(response, "/reader");
            return;
        }
        request.setAttribute(CurrentUser.ATTRIBUTE, user);
        chain.doFilter(request, response);
    }

    /**
     * Decodes the request path the way Spring MVC will match it, or returns null for paths
     * whose meaning could differ between this filter and the handler mapping
     * (matrix parameters, dot segments, encoded separators).
     */
    static String canonicalPath(HttpServletRequest request) {
        String raw = request.getRequestURI().substring(request.getContextPath().length());
        String lower = raw.toLowerCase(Locale.ROOT);
        if (raw.isEmpty() || raw.charAt(0) != '/' || lower.contains(";") || lower.contains("%2e")
                || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%3b") || raw.contains("\\")) {
            return null;
        }
        String path;
        try {
            path = UriUtils.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String[] segments = path.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            boolean trailing = i == segments.length - 1;
            if ((segment.isEmpty() && !trailing) || segment.equals(".") || segment.equals("..")) return null;
        }
        return path;
    }

    static Access classify(String path, String method) {
        if (path.equals("/login") || path.equals("/logout")
                || path.equals("/favicon.svg") || path.equals("/favicon.ico")
                || (path.lastIndexOf('/') == 0 && FrontendAssetResource.ASSETS.contains(path.substring(1)))) {
            return Access.PUBLIC;
        }
        if (path.equals("/index") || path.startsWith("/index/")) return Access.ADMIN;
        // Radar runs spend AI budget for everyone; only admins may start or resume them.
        if (path.startsWith("/reader/radar/runs") && !isSafe(method)) return Access.ADMIN;
        if (path.equals("/reader") || path.startsWith("/reader/")
                || path.equals("/account") || path.startsWith("/account/")) {
            return Access.READER;
        }
        return Access.FEED;
    }

    private record Authenticated(UserAccount user, boolean viaCookie) {}

    private Authenticated authenticate(HttpServletRequest request, boolean feed) throws SQLException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return wrap(auth.authenticateToken(header.substring(7)), false);
            }
            if (header.regionMatches(true, 0, "Basic ", 0, 6)) {
                String secret = basicPassword(header.substring(6));
                UserAccount user = auth.authenticateToken(secret);
                if (user == null && feed && isSafe(request.getMethod())) user = auth.authenticateFeedKey(secret);
                return wrap(user, false);
            }
        }
        String cookie = cookieValue(request);
        if (cookie != null) {
            UserAccount user = auth.authenticateToken(cookie);
            if (user != null) return new Authenticated(user, true);
        }
        if (feed && isSafe(request.getMethod())) {
            return wrap(auth.authenticateFeedKey(request.getParameter(FEED_KEY_PARAM)), false);
        }
        return null;
    }

    private static Authenticated wrap(UserAccount user, boolean viaCookie) {
        return user == null ? null : new Authenticated(user, viaCookie);
    }

    private static String basicPassword(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded.strip()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon < 0 ? decoded : decoded.substring(colon + 1);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void unauthenticated(HttpServletRequest request, HttpServletResponse response,
                                 String path, Access access) throws IOException {
        String target = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        String login = "/login?next=" + URLEncoder.encode(target, StandardCharsets.UTF_8);
        if (request.getHeader("HX-Request") != null) {
            response.setHeader("HX-Redirect", login);
            response.sendError(401, "请先登录");
            return;
        }
        boolean page = "GET".equals(request.getMethod()) && (access != Access.FEED || "/".equals(path));
        if (page) {
            redirect(response, login);
            return;
        }
        if (access == Access.FEED) {
            // Lets RSS readers that support HTTP auth prompt for credentials.
            response.setHeader("WWW-Authenticate", "Basic realm=\"fetch-news\", charset=\"UTF-8\"");
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"unauthorized\",\"detail\":"
                    + "\"append ?key=<feed key> to the feed URL, or use HTTP Basic auth with your token\"}");
            return;
        }
        response.sendError(401, "请先登录");
    }

    /** Relative redirects stay correct behind a TLS-terminating tunnel such as frp. */
    private static void redirect(HttpServletResponse response, String location) {
        response.setStatus(302);
        response.setHeader("Location", location);
    }

    private static boolean isSafe(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    /**
     * Rejects cookie-authenticated writes coming from another site. The login cookie is
     * SameSite=Lax as well; this covers browsers or proxies where that is not enough.
     */
    static boolean sameOrigin(HttpServletRequest request) {
        String source = request.getHeader("Origin");
        if (source == null || source.isBlank() || "null".equals(source)) source = request.getHeader("Referer");
        if (source == null || source.isBlank()) return true;
        String sourceHost;
        try {
            URI uri = URI.create(source.strip());
            if (uri.getHost() == null) return false;
            sourceHost = uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (String header : new String[] {"X-Forwarded-Host", "Host"}) {
            String value = request.getHeader(header);
            if (value == null) continue;
            String host = value.split(",")[0].strip().toLowerCase(Locale.ROOT);
            if (sameHost(sourceHost, host)) return true;
        }
        return false;
    }

    private static boolean sameHost(String source, String host) {
        if (source.equals(host)) return true;
        // Default ports are omitted by one side but not always the other.
        return stripDefaultPort(source).equals(stripDefaultPort(host));
    }

    private static String stripDefaultPort(String host) {
        return host.endsWith(":80") || host.endsWith(":443") ? host.substring(0, host.lastIndexOf(':')) : host;
    }

    private static boolean hasCookie(HttpServletRequest request) {
        return cookieValue(request) != null;
    }

    static String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) return cookie.getValue();
        }
        return null;
    }

    /** Builds the login cookie header value; {@code maxAgeSeconds = 0} deletes it. */
    public static String cookieHeader(HttpServletRequest request, String value, long maxAgeSeconds) {
        boolean secure = request.isSecure()
                || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        return COOKIE + "=" + value + "; Path=/; Max-Age=" + maxAgeSeconds + "; HttpOnly; SameSite=Lax"
                + (secure ? "; Secure" : "");
    }

    private static void clearCookie(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader("Set-Cookie", cookieHeader(request, "", 0));
    }
}
