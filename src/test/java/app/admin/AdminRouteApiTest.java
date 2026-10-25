package app.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

import app.AppRuntime;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Spring Boot integration tests for the admin JSON route API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminRouteApiTest {
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
