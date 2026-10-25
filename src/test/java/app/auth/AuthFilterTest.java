package app.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

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
    void sameOriginAcceptsMatchingOrForwardedHostsOnly() {
        assertTrue(AuthFilter.sameOrigin(request("https://news.example.com", "news.example.com", null)));
        assertTrue(AuthFilter.sameOrigin(request("http://localhost:14001", "localhost:14001", null)));
        assertTrue(AuthFilter.sameOrigin(request("https://news.example.com", "127.0.0.1:14001", "news.example.com")));
        assertTrue(AuthFilter.sameOrigin(request("https://news.example.com:443", "news.example.com", null)));
        assertTrue(AuthFilter.sameOrigin(request(null, "news.example.com", null)));
        assertFalse(AuthFilter.sameOrigin(request("https://evil.example", "news.example.com", null)));
        assertFalse(AuthFilter.sameOrigin(request("https://news.example.com.evil.example", "news.example.com", null)));
    }

    @Test
    void loginRedirectsStayOnThisSite() {
        assertEquals("/reader/unread?q=x", AuthResource.safeNext("/reader/unread?q=x"));
        assertEquals("/reader", AuthResource.safeNext("//evil.example"));
        assertEquals("/reader", AuthResource.safeNext("https://evil.example"));
        assertEquals("/reader", AuthResource.safeNext("/\\evil.example"));
        assertEquals("/reader", AuthResource.safeNext("/login"));
        assertEquals("/reader", AuthResource.safeNext(""));
    }

    private static MockHttpServletRequest request(String origin, String host, String forwardedHost) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/reader/read");
        if (origin != null) request.addHeader("Origin", origin);
        request.addHeader("Host", host);
        if (forwardedHost != null) request.addHeader("X-Forwarded-Host", forwardedHost);
        return request;
    }
}
