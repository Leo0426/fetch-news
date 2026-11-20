package app.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthFilterTest {
    @Test
    void classifiesAdminReaderFeedAndPublicPaths() {
        assertEquals(AuthFilter.Access.ADMIN, AuthFilter.classify("/index", "GET"));
        assertEquals(AuthFilter.Access.ADMIN, AuthFilter.classify("/index/users", "POST"));
        assertEquals(AuthFilter.Access.ADMIN, AuthFilter.classify("/reader/radar/runs/x/resume", "POST"));
        assertEquals(AuthFilter.Access.READER, AuthFilter.classify("/reader/radar/runs/x", "GET"));
        assertEquals(AuthFilter.Access.READER, AuthFilter.classify("/reader/read", "PUT"));
        assertEquals(AuthFilter.Access.READER, AuthFilter.classify("/account/opml", "GET"));
        assertEquals(AuthFilter.Access.PUBLIC, AuthFilter.classify("/login", "POST"));
        assertEquals(AuthFilter.Access.PUBLIC, AuthFilter.classify("/reader.js", "GET"));
        assertEquals(AuthFilter.Access.FEED, AuthFilter.classify("/hackernews", "GET"));
        assertEquals(AuthFilter.Access.FEED, AuthFilter.classify("/nested/reader.js", "GET"));
        assertEquals(AuthFilter.Access.FEED, AuthFilter.classify("/indexes", "GET"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index;a=b/users", "/reader/%2e%2e/index", "/reader/../index", "/x//index",
            "/index%2Fusers", "/reader/./x", "/a%3Bb"})
    void rejectsPathsThatHandlerMappingCouldReadDifferently(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        assertNull(AuthFilter.canonicalPath(request));
    }

    @Test
    void decodesOrdinaryPaths() {
        assertEquals("/", AuthFilter.canonicalPath(new MockHttpServletRequest("GET", "/")));
        assertEquals("/telegram/a b", AuthFilter.canonicalPath(new MockHttpServletRequest("GET", "/telegram/a%20b")));
        assertEquals("/index/", AuthFilter.canonicalPath(new MockHttpServletRequest("GET", "/index/")));
    }

    @Test
    void sameOriginRequiresMatchingSchemeHostnameAndEffectivePort() {
        assertTrue(AuthFilter.sameOrigin(request("https", "https://news.example:443", "news.example")));
        assertTrue(AuthFilter.sameOrigin(request("http", "http://localhost:14001", "localhost:14001")));
        assertTrue(AuthFilter.sameOrigin(request("https", null, "news.example")));
        assertFalse(AuthFilter.sameOrigin(request("https", "null", "news.example")));
        assertFalse(AuthFilter.sameOrigin(request("https", "http://news.example", "news.example")));
        assertFalse(AuthFilter.sameOrigin(request("https", "https://news.example:80", "news.example")));
        assertFalse(AuthFilter.sameOrigin(request("http", "http://localhost", "localhost:14001")));
        assertFalse(AuthFilter.sameOrigin(request("https", "https://news.example.invalid", "news.example")));
    }

    @Test
    void tlsTerminatingProxySuppliesThePublicOrigin() {
        var proxy = request("http", "https://news.example", "localhost:14001");
        proxy.addHeader("X-Forwarded-Host", "news.example");
        proxy.addHeader("X-Forwarded-Proto", "https");
        assertTrue(AuthFilter.sameOrigin(proxy));
        proxy.removeHeader("Origin");
        proxy.addHeader("Referer", "https://news.example/account");
        assertTrue(AuthFilter.sameOrigin(proxy));
        proxy.addHeader("Origin", "http://localhost:14001");
        assertFalse(AuthFilter.sameOrigin(proxy));
        proxy.removeHeader("Origin");
        proxy.addHeader("X-Forwarded-Port", "8443");
        assertFalse(AuthFilter.sameOrigin(proxy));
        proxy.addHeader("Origin", "https://news.example:8443");
        assertTrue(AuthFilter.sameOrigin(proxy));
    }

    @Test
    void basicAuthWritesCheckBrowserOriginAndStillAllowNativeClients(@TempDir Path temp) throws Exception {
        try (FeedStore store = new FeedStore(temp.resolve("auth.db"), new ObjectMapper())) {
            AuthService auth = new AuthService(store, true);
            String token = "mock-account-token";
            auth.bootstrap(token);
            AuthFilter filter = new AuthFilter(auth);
            byte[] credentials = (":" + token).getBytes(StandardCharsets.UTF_8);
            String basic = "Basic " + Base64.getEncoder().encodeToString(credentials);
            assertEquals(403, filterWrite(filter, basic, "https://unrelated.example"));
            assertEquals(204, filterWrite(filter, basic, "https://news.example"));
            assertEquals(204, filterWrite(filter, basic, null));
            assertEquals(204, filterWrite(filter, "Bearer " + token, null));
        }
    }

    private static int filterWrite(AuthFilter filter, String authorization, String origin) throws Exception {
        var req = request("https", origin, "news.example");
        req.addHeader("Authorization", authorization);
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, (request, response) -> res.setStatus(204));
        return res.getStatus();
    }

    @Test
    void loginRedirectsStayOnThisSite() {
        assertEquals("/reader/search?q=model", AuthResource.safeNext("/reader/search?q=model"));
        assertEquals("/reader", AuthResource.safeNext("//evil.example"));
        assertEquals("/reader", AuthResource.safeNext("https://evil.example"));
        assertEquals("/reader", AuthResource.safeNext("/\\evil.example"));
        assertEquals("/reader", AuthResource.safeNext("/login"));
        assertEquals("/reader", AuthResource.safeNext(""));
    }

    private static MockHttpServletRequest request(String scheme, String origin, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/reader/read");
        request.setScheme(scheme);
        if (origin != null) request.addHeader("Origin", origin);
        request.addHeader("Host", host);
        return request;
    }
}
