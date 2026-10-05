package app;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Spring Boot integration tests for public feed and admin HTTP endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RssLiteResourceTest {
    @LocalServerPort
    int port;

    @Autowired
    AppRuntime runtime;

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        runtime.routeConfigStore().remove("/mritd/blog");
        runtime.routeConfigStore().remove("/mritd-short");
    }

    /**
     * Verifies that missing routes return the normalized not-found error.
     */
    @Test
    void unknownRouteReturnsConsistentError() {
        given()
                .when().get("/unknown")
                .then()
                .statusCode(404)
                .body("error", equalTo("route not found"));
    }

    /**
     * Verifies that the admin API lists built-in routes.
     */
    @Test
    void adminApiListsRoutes() {
        given()
                .when().get("/index/api/routes")
                .then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .body("find { it.path == '/mritd/blog' }.enabled", equalTo(false));
    }

    /**
     * Verifies that the admin HTML page is served.
     */
    @Test
    void adminPageIsAvailable() {
        given()
                .when().get("/index")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("路由控制台"))
                .body(containsString("数据统计"))
                .body(containsString("/htmx.min.js"))
                .body(containsString("/alpine.min.js"))
                .body(containsString("x-data"))
                .body(containsString("hx-get"));
    }

    /**
     * Verifies that the route edit modal fragment contains form fields.
     */
    @Test
    void adminEditModalHasFormFields() {
        String html = given()
                .when().get("/index/routes/edit?path=/mritd/blog")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("挂载路径"))
                .body(containsString("来源路由"))
                .body(containsString("data-source-path=\"/mritd/blog\""))
                .body(not(containsString("[[${")))
                .body(containsString("hx-post"))
                .extract().asString();
        var source = Jsoup.parse(html).selectFirst("#modal-sourcePath option[selected]");
        assertNotNull(source, "The current source must be available in the route editor");
        assertEquals("/mritd/blog", source.attr("value"));
    }

    /**
     * Verifies that frontend assets are served before the feed catch-all route.
     */
    @Test
    void frontendAssetsAreAvailable() {
        given()
                .when().get("/htmx.min.js")
                .then()
                .statusCode(200)
                .contentType(containsString("javascript"));

        given()
                .when().get("/alpine.min.js")
                .then()
                .statusCode(200)
                .contentType(containsString("javascript"));
    }

    @Test
    void interactionScriptsAreServedBeforeFeedRoutes() {
        for (String[] asset : new String[][] {
                {"ui.js", "FetchNewsUI"}, {"reader.js", "readerState"}, {"admin.js", "appShell"}}) {
            given().when().get("/" + asset[0]).then()
                    .statusCode(200)
                    .contentType(containsString("javascript"))
                    .body(containsString(asset[1]));
        }
    }

    @Test
    void readerPagesRenderNavigationAndInteractionAssets() {
        for (String path : new String[] {"/reader", "/reader/bookmarks", "/reader/search", "/reader/briefing", "/reader/unread"}) {
            given().when().get(path).then()
                    .statusCode(200)
                    .contentType(containsString("text/html"))
                    .body(containsString("阅读导航"))
                    .body(containsString("/ui.js"));
        }
    }

    @Test
    void unreadStylesAndCursorValidationAreAvailableOverHttp() {
        given().when().get("/unread.css").then()
                .statusCode(200).contentType(containsString("text/css"));
        for (String cursor : new String[] {"0", "-1", "invalid", "9223372036854775808"}) {
            given().queryParam("before", cursor).when().get("/reader/unread").then().statusCode(400);
        }
    }

    @Test
    void searchStylesAndInputValidationAreAvailableOverHttp() {
        given().when().get("/search.css").then()
                .statusCode(200).contentType(containsString("text/css"));
        for (String query : new String[] {"字", "𠀀", "字".repeat(201)}) {
            given().queryParam("q", query).when().get("/reader/search").then().statusCode(400)
                    .body(containsString("请输入 2–200 个字符"));
        }
        given().queryParam("q", "不存在的搜索词").when().get("/reader/search").then().statusCode(200);
    }

    /**
     * Verifies that the HTMX route editor can save form-encoded route settings.
     */
    @Test
    void adminHtmxFormCanSaveRoute() {
        var response = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("path", "/mritd/blog")
                .formParam("sourcePath", "/mritd/blog")
                .formParam("enabled", "false")
                .formParam("routeCacheTtlSeconds", "120")
                .formParam("detailCacheTtlSeconds", "900")
                .when().post("/index/routes")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(equalTo(""))
                .extract().response();

        JsonPath triggers = JsonPath.from(response.header("HX-Trigger"));
        assertTrue(triggers.getBoolean("closemodal"));
        assertTrue(triggers.getBoolean("routeSaved"));

        given()
                .when().get("/index/api/routes")
                .then()
                .statusCode(200)
                .body("find { it.path == '/mritd/blog' }.enabled", equalTo(false))
                .body("find { it.path == '/mritd/blog' }.routeCacheTtlSeconds", equalTo(120))
                .body("find { it.path == '/mritd/blog' }.detailCacheTtlSeconds", equalTo(900));
    }

    /**
     * Verifies that the admin API can disable and re-enable a route.
     */
    @Test
    void adminApiCanDisableAndReEnableRoute() {
        given()
                .contentType("application/json")
                .body("""
                        {"path": "/mritd/blog", "enabled": true}
                        """)
                .when().put("/index/api/routes")
                .then()
                .statusCode(200)
                .body("enabled", equalTo(true));

        given()
                .contentType("application/json")
                .body("""
                        {
                          "path": "/mritd/blog",
                          "enabled": false,
                          "routeCacheTtlSeconds": 300,
                          "detailCacheTtlSeconds": 1800
                        }
                        """)
                .when().put("/index/api/routes")
                .then()
                .statusCode(200)
                .body("enabled", equalTo(false));

        given()
                .when().get("/mritd/blog")
                .then()
                .statusCode(404)
                .body("error", equalTo("route disabled"));

        given()
                .contentType("application/json")
                .body("""
                        {
                          "path": "/mritd/blog",
                          "enabled": true,
                          "routeCacheTtlSeconds": 300,
                          "detailCacheTtlSeconds": 1800
                        }
                        """)
                .when().put("/index/api/routes")
                .then()
                .statusCode(200)
                .body("enabled", equalTo(true));
    }

    /**
     * Verifies that saving a config for an unknown route is rejected.
     */
    @Test
    void adminApiRejectsUnknownRoute() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "path": "/missing",
                          "enabled": true,
                          "routeCacheTtlSeconds": 300,
                          "detailCacheTtlSeconds": 1800
                        }
                        """)
                .when().put("/index/api/routes")
                .then()
                .statusCode(404)
                .body("error", equalTo("route not found"));
    }

    /**
     * Verifies that non-positive route cache TTL values are rejected.
     */
    @Test
    void adminApiRejectsInvalidRouteCacheTtl() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "path": "/mritd/blog",
                          "enabled": true,
                          "routeCacheTtlSeconds": 0,
                          "detailCacheTtlSeconds": 1800
                        }
                        """)
                .when().put("/index/api/routes")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid route parameter"))
                .body("detail", equalTo("route cache TTL must be positive"));
    }

    /**
     * Verifies that non-positive detail cache TTL values are rejected.
     */
    @Test
    void adminApiRejectsInvalidDetailCacheTtl() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "path": "/mritd/blog",
                          "enabled": true,
                          "routeCacheTtlSeconds": 300,
                          "detailCacheTtlSeconds": 0
                        }
                        """)
                .when().put("/index/api/routes")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid route parameter"))
                .body("detail", equalTo("detail cache TTL must be positive"));
    }

    /**
     * Verifies that an empty admin API request body is rejected.
     */
    @Test
    void adminApiRejectsEmptyBody() {
        given()
                .contentType("application/json")
                .when().put("/index/api/routes")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid route parameter"))
                .body("detail", equalTo("route config body is required"));
    }

    /**
     * Verifies that mount aliases must point at a registered source route.
     */
    @Test
    void adminApiRejectsUnknownSourceRouteForMountAlias() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "path": "/short",
                          "sourcePath": "/missing",
                          "enabled": true,
                          "routeCacheTtlSeconds": 300,
                          "detailCacheTtlSeconds": 1800
                        }
                        """)
                .when().put("/index/api/routes")
                .then()
                .statusCode(404)
                .body("error", equalTo("route not found"));
    }

    /**
     * Verifies that a saved mount alias is returned by the admin API.
     */
    @Test
    void adminApiCanAddMountAliasRoute() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "path": "/mritd-short",
                          "sourcePath": "/mritd/blog",
                          "enabled": true,
                          "routeCacheTtlSeconds": 300,
                          "detailCacheTtlSeconds": 1800
                        }
                        """)
                .when().put("/index/api/routes")
                .then()
                .statusCode(200)
                .body("path", equalTo("/mritd-short"))
                .body("sourcePath", equalTo("/mritd/blog"));
    }
}
