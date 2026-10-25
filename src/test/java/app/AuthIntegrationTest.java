package app;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.auth.AuthFilter;
import app.auth.UserAccount;
import app.core.FeedItem;
import app.core.RouteConfig;
import app.store.FeedStore;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"fetch-news.auth.enabled=true", "fetch-news.admin-token=" + AuthIntegrationTest.ADMIN_TOKEN})
class AuthIntegrationTest {
    static final String ADMIN_TOKEN = "test-admin-token-0123456789";
    private static final Pattern ISSUED_TOKEN = Pattern.compile(">(fn_[A-Za-z0-9_-]+)<");

    @LocalServerPort
    int port;

    @Autowired
    FeedStore store;

    @Autowired
    AppRuntime runtime;

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
    }

    @Test
    void anonymousVisitorsAreSentToLoginAndFeedsAskForCredentials() {
        given().redirects().follow(false).when().get("/reader")
                .then().statusCode(302).header("Location", containsString("/login?next=%2Freader"));
        given().redirects().follow(false).when().get("/index/users")
                .then().statusCode(302).header("Location", containsString("/login"));
        given().header("HX-Request", "true").when().get("/index/users")
                .then().statusCode(401).header("HX-Redirect", containsString("/login"));
        given().when().get("/no-such-feed")
                .then().statusCode(401).header("WWW-Authenticate", containsString("Basic"));
        given().when().get("/admin.css").then().statusCode(200);
        given().when().get("/login").then().statusCode(200).body(containsString("访问令牌"));
    }

    @Test
    void loginSetsAnHttpOnlyCookieAndRejectsWrongTokens() {
        given().formParam("token", "fn_wrong").when().post("/login")
                .then().statusCode(401).body(containsString("令牌无效"));
        Response response = given().redirects().follow(false)
                .formParam("token", ADMIN_TOKEN).formParam("next", "https://evil.example/")
                .when().post("/login");
        assertEquals(303, response.statusCode());
        assertEquals("/reader", response.header("Location"));
        assertTrue(response.header("Set-Cookie").contains("HttpOnly"));
        assertTrue(response.header("Set-Cookie").contains("SameSite=Lax"));
        given().cookie(AuthFilter.COOKIE, ADMIN_TOKEN).when().get("/index").then().statusCode(200);
    }

    @Test
    void membersReadWithTheirOwnStateButCannotAdminister() {
        String member = createAccount("member-" + shortId(), "MEMBER");
        String link = "https://example.com/auth/" + UUID.randomUUID();
        saveItem(link);

        given().cookie(AuthFilter.COOKIE, member).when().get("/reader").then().statusCode(200)
                .body(not(containsString("href=\"/index\"")));
        given().cookie(AuthFilter.COOKIE, member).redirects().follow(false).when().get("/index")
                .then().statusCode(302).header("Location", "/reader");
        given().cookie(AuthFilter.COOKIE, member).header("HX-Request", "true").when().get("/index/users")
                .then().statusCode(403);
        given().cookie(AuthFilter.COOKIE, member).when().post("/reader/radar/runs").then().statusCode(403);

        given().cookie(AuthFilter.COOKIE, member).header("Origin", "http://localhost:" + port)
                .formParam("link", link).formParam("read", true).when().put("/reader/read")
                .then().statusCode(200).body(equalTo("read"));
        given().cookie(AuthFilter.COOKIE, member).header("Origin", "http://localhost:" + port)
                .formParam("link", link).formParam("bookmarked", true).when().put("/reader/article/bookmark")
                .then().statusCode(200);

        UserAccount account = accountFor(member);
        assertTrue(isRead(account.id(), link));
        assertFalse(isRead(UserAccount.OWNER_ID, link), "another account's read state is untouched");
        given().header("Authorization", "Bearer " + ADMIN_TOKEN).when().get("/reader/bookmarks")
                .then().statusCode(200).body(not(containsString(link)));
        given().header("Authorization", "Bearer " + member).when().get("/reader/bookmarks")
                .then().statusCode(200).body(containsString(link));
    }

    @Test
    void feedKeysOnlyUnlockFeeds() {
        String member = createAccount("feeds-" + shortId(), "MEMBER");
        String key = accountFor(member).feedKey();
        runtime.routeConfigStore().save(new RouteConfig("/solidot/news", true, 300, 1800));

        given().queryParam("key", key).when().get("/no-such-feed")
                .then().statusCode(404);
        given().auth().preemptive().basic("anyone", member).when().get("/no-such-feed").then().statusCode(404);
        given().queryParam("key", "fk_wrong").when().get("/no-such-feed").then().statusCode(401);
        given().redirects().follow(false).queryParam("key", key).when().get("/reader")
                .then().statusCode(302).header("Location", containsString("/login"));
        given().cookie(AuthFilter.COOKIE, member).when().get("/account").then().statusCode(200)
                .body(containsString(key));
        given().cookie(AuthFilter.COOKIE, member).when().get("/account/opml").then().statusCode(200)
                .body(containsString("/solidot/news?key=" + key));
        runtime.routeConfigStore().remove("/solidot/news");
    }

    @Test
    void revokedTokensAndCrossSiteWritesAreRejected() {
        String name = "revoked-" + shortId();
        String member = createAccount(name, "MEMBER");
        long id = accountFor(member).id();

        given().cookie(AuthFilter.COOKIE, member).header("Origin", "https://evil.example")
                .formParam("links", "https://example.com/x").when().post("/reader/read-all")
                .then().statusCode(403);
        given().cookie(AuthFilter.COOKIE, member).redirects().follow(false).when().get("/index;x=1/users")
                .then().statusCode(400);

        adminPost("/index/users/" + id + "/disabled?disabled=true").then().statusCode(200);
        given().redirects().follow(false).cookie(AuthFilter.COOKIE, member).when().get("/reader")
                .then().statusCode(302).header("Set-Cookie", containsString("Max-Age=0"));
        adminPost("/index/users/" + UserAccount.OWNER_ID + "/disabled?disabled=true")
                .then().statusCode(200).body(containsString("不能停用当前登录的账户"));
    }

    private String createAccount(String name, String role) {
        String html = given().header("Authorization", "Bearer " + ADMIN_TOKEN)
                .formParam("name", name).formParam("role", role)
                .when().post("/index/users")
                .then().statusCode(200).extract().asString();
        Matcher matcher = ISSUED_TOKEN.matcher(html);
        assertTrue(matcher.find(), () -> "issued token is shown once: " + html);
        return matcher.group(1);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Response adminPost(String path) {
        return given().header("Authorization", "Bearer " + ADMIN_TOKEN).when().post(path);
    }

    private UserAccount accountFor(String token) {
        try {
            UserAccount user = store.accounts().findUserByTokenHash(app.auth.AuthTokens.hash(token));
            assertNotNull(user);
            return user;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private boolean isRead(long userId, String link) {
        try {
            return store.reading().isRead(userId, link);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void saveItem(String link) {
        try {
            store.saveItems("/auth-test", List.of(new FeedItem("Auth item", link, "Body",
                    Instant.parse("2026-01-01T00:00:00Z"), null, List.of())));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
