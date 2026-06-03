package app;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Spring Boot integration tests for public feed and admin HTTP endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RssLiteResourceTest {
    @LocalServerPort
    int port;

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
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
                .body("find { it.path == '/zed/blog' }.enabled", equalTo(true));
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
        given()
                .when().get("/index/routes/edit?path=/zed/blog")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("挂载路径"))
                .body(containsString("来源路由"))
                .body(containsString("hx-post"));
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

    /**
     * Verifies that the HTMX route editor can save form-encoded route settings.
     */
    @Test
    void adminHtmxFormCanSaveRoute() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("path", "/zed/blog")
                .formParam("sourcePath", "/zed/blog")
                .formParam("enabled", "false")
                .formParam("routeCacheTtlSeconds", "300")
                .formParam("detailCacheTtlSeconds", "1800")
                .when().post("/index/routes")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("已保存"))
                .body(containsString("hx-swap-oob"))
                .body(containsString("/zed/blog"))
                .body(containsString("off"));

        given()
                .contentType("application/json")
                .body("""
                        {
                          "path": "/zed/blog",
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
     * Verifies that the admin API can disable and re-enable a route.
     */
    @Test
    void adminApiCanDisableAndReEnableRoute() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "path": "/zed/blog",
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
                .when().get("/zed/blog")
                .then()
                .statusCode(404)
                .body("error", equalTo("route disabled"));

        given()
                .contentType("application/json")
                .body("""
                        {
                          "path": "/zed/blog",
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
                          "path": "/zed/blog",
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
                          "path": "/zed/blog",
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
                          "path": "/zed-short",
                          "sourcePath": "/zed/blog",
                          "enabled": true,
                          "routeCacheTtlSeconds": 300,
                          "detailCacheTtlSeconds": 1800
                        }
                        """)
                .when().put("/index/api/routes")
                .then()
                .statusCode(200)
                .body("path", equalTo("/zed-short"))
                .body("sourcePath", equalTo("/zed/blog"));
    }
}
