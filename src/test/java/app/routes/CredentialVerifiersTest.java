package app.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.CacheService;
import app.core.CredentialCheck;
import app.core.FetchClient;
import app.core.RouteError;
import app.core.RouteException;
import app.routes.bilibili.BilibiliCookieVerifier;
import app.routes.twitter.TwitterCookieVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CredentialVerifiersTest {
    private static final String COOKIE = "auth_token=abc; ct0=csrf";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void xCookieIsValidWhenTheProfileLookupSucceedsWithIt() {
        List<Map<String, String>> sent = new ArrayList<>();
        FetchClient client = new FetchClient() {
            @Override
            public String get(String url) {
                return "<html></html>";
            }

            @Override
            public String get(String url, Map<String, String> headers) {
                if (url.equals("https://x.com")) return "<html></html>";
                assertTrue(url.contains("/UserByScreenName?"), url);
                sent.add(headers);
                return "{\"data\":{\"user\":{\"result\":{\"legacy\":{\"screen_name\":\"X\"}}}}}";
            }
        };

        CredentialCheck check = new TwitterCookieVerifier(client, mapper, new CacheService()).verify(COOKIE);

        assertTrue(check.valid(), check.message());
        assertEquals(COOKIE, sent.getFirst().get("cookie"));
        assertEquals("csrf", sent.getFirst().get("x-csrf-token"));
    }

    @Test
    void xCookieIsInvalidWhenFieldsAreMissingOrXRejectsIt() {
        FetchClient rejecting = url -> {
            if (url.equals("https://x.com")) return "<html></html>";
            throw new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "upstream returned HTTP 401");
        };
        var verifier = new TwitterCookieVerifier(rejecting, mapper, new CacheService());

        assertFalse(verifier.verify("").valid());
        CredentialCheck missing = verifier.verify("auth_token=abc");
        assertFalse(missing.valid());
        assertTrue(missing.message().contains("ct0"), missing.message());
        CredentialCheck rejected = verifier.verify(COOKIE);
        assertFalse(rejected.valid());
        assertTrue(rejected.message().contains("HTTP 401"), rejected.message());
    }

    @Test
    void bilibiliLoginCookieMustBelongToTheConfiguredUid() {
        FetchClient client = new FetchClient() {
            @Override
            public String get(String url) {
                throw new AssertionError("cookie header required");
            }

            @Override
            public String get(String url, Map<String, String> headers) {
                assertEquals("https://api.bilibili.com/x/web-interface/nav", url);
                return headers.get("Cookie").contains("SESSDATA=good")
                        ? "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":2267573,\"uname\":\"主号\"}}"
                        : "{\"code\":-101,\"data\":{\"isLogin\":false}}";
            }
        };
        var verifier = new BilibiliCookieVerifier(client, mapper);

        CredentialCheck valid = verifier.verifyLogin("2267573", "主号", "SESSDATA=good");
        assertTrue(valid.valid(), valid.message());
        assertTrue(valid.label().contains("2267573（主号）"), valid.label());
        CredentialCheck otherAccount = verifier.verifyLogin("1", null, "SESSDATA=good");
        assertFalse(otherAccount.valid());
        assertTrue(otherAccount.message().contains("2267573"), otherAccount.message());
        assertFalse(verifier.verifyLogin("2267573", null, "SESSDATA=expired").valid());
    }

    @Test
    void bilibiliPublicCookieNeedsBuvid3() {
        var verifier = new BilibiliCookieVerifier(url -> {
            throw new AssertionError("public cookie is checked offline");
        }, mapper);

        assertTrue(verifier.verifyPublic("buvid3=a; buvid4=b").valid());
        assertFalse(verifier.verifyPublic("buvid4=b").valid());
        assertFalse(verifier.verifyPublic(" ").valid());
    }
}
