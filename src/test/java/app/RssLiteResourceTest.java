package app;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.RouteConfig;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
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

    @Test
    void curatedSocialSourcesAppearAsConcreteSubscriptionsWithCredentialGuidance() {
        Map<String, String> sources = Map.ofEntries(
                Map.entry("/telegram/scitech_fans", "科技资讯"),
                Map.entry("/telegram/newlearner", "科技资讯"),
                Map.entry("/telegram/solidot", "科技资讯"),
                Map.entry("/x/openai", "AI"),
                Map.entry("/x/anthropic", "AI"),
                Map.entry("/x/huggingface", "AI"),
                Map.entry("/x/karpathy", "AI"),
                Map.entry("/x/simonw", "AI"),
                Map.entry("/x/github", "软件开发"));
        sources.forEach((path, category) -> {
            assertEquals(category, runtime.routeRegistry().categories().get(path), path);
            var route = runtime.routeRegistry().routes().stream()
                    .filter(candidate -> candidate.path().equals(path)).findFirst().orElseThrow();
            assertTrue(route.recommendedScheduleMinutes() > 0, path);
            assertEquals(false, runtime.routeConfigStore().get(path).enabled());
            assertEquals(0, runtime.routeConfigStore().get(path).scheduleMinutes());
            given().get(path).then().statusCode(404).body("error", equalTo("route disabled"));
        });
        String telegram = given().param("category", "科技资讯").get("/index/subscriptions")
                .then().statusCode(200).extract().asString();
        assertTrue(telegram.contains("科技爱好者周刊"));
        assertTrue(telegram.contains("Newlearner"));
        assertTrue(telegram.contains("Solidot"));
        String twitter = given().param("category", "AI").get("/index/subscriptions")
                .then().statusCode(200).extract().asString();
        assertTrue(twitter.contains("Karpathy"));
        assertTrue(twitter.contains("Simon Willison"));
        assertTrue(twitter.contains("Cookie"));
        assertNotNull(runtime.routeRegistry().resolve("/telegram/channel/example").handler());
    }

    @Test
    void curatedSourcesAreBuiltInAndCategorizedWithoutEnablingBackgroundWork() {
        Map<String, String> sources = Map.ofEntries(
                Map.entry("/openai/news", "AI"), Map.entry("/huggingface/blog", "AI"),
                Map.entry("/google/research", "AI"),
                Map.entry("/github/open-source", "软件开发"), Map.entry("/github/changelog", "软件开发"),
                Map.entry("/ollama/releases", "AI"),
                Map.entry("/huggingface/transformers/releases", "AI"),
                Map.entry("/chinanews/society", "时事要闻"), Map.entry("/un/news/zh", "时事要闻"),
                Map.entry("/rthk/local", "时事要闻"), Map.entry("/guardian/world", "时事要闻"),
                Map.entry("/nasa/news", "科学"), Map.entry("/quanta/magazine", "科学"),
                Map.entry("/sciencenews/latest", "科学"),
                Map.entry("/carbonbrief/news", "科学"), Map.entry("/noaa/news", "科学"),
                Map.entry("/cloudflare/blog", "软件开发"), Map.entry("/martinfowler/articles", "软件开发"),
                Map.entry("/netflix/techblog", "软件开发"), Map.entry("/infoq/china", "软件开发"),
                Map.entry("/google/security", "网络安全"), Map.entry("/krebs/security", "网络安全"),
                Map.entry("/schneier/security", "网络安全"),
                Map.entry("/ecb/blog", "财经商业"), Map.entry("/federalreserve/feds-notes", "财经商业"),
                Map.entry("/chinanews/finance", "财经商业"),
                Map.entry("/smashing/articles", "设计艺术"), Map.entry("/aeon/essays", "人文社会"),
                Map.entry("/chinanews/culture", "人文社会"),
                Map.entry("/ruanyifeng/blog", "科技资讯"), Map.entry("/solidot/news", "科技资讯"),
                Map.entry("/kff/health-news", "健康医学"),
                Map.entry("/stat/news", "健康医学"),
                Map.entry("/psyche/magazine", "人文社会"),
                Map.entry("/aps/research", "科学"),
                Map.entry("/edsurge/news", "人文社会"),
                Map.entry("/hechinger/report", "人文社会"),
                Map.entry("/jstor/daily", "人文社会"),
                Map.entry("/public-domain-review/latest", "人文社会"),
                Map.entry("/colossal/latest", "设计艺术"),
                Map.entry("/aperture/articles", "设计艺术"),
                Map.entry("/dezeen/latest", "设计艺术"),
                Map.entry("/archdaily/latest", "设计艺术"),
                Map.entry("/ourworldindata/articles", "人文社会"),
                Map.entry("/pewresearch/publications", "人文社会"),
                Map.entry("/hackaday/posts", "科技资讯"),
                Map.entry("/raspberrypi/news", "科技资讯"),
                Map.entry("/godot/news", "游戏"),
                Map.entry("/factorio/blog", "游戏"),
                Map.entry("/gcores/articles", "游戏"),
                Map.entry("/yystv/articles", "游戏"),
                Map.entry("/deepmind/blog", "AI"), Map.entry("/bair/blog", "AI"),
                Map.entry("/simonwillison/weblog", "AI"),
                Map.entry("/npr/world", "时事要闻"),
                Map.entry("/lwn/headlines", "软件开发"),
                Map.entry("/ftchinese/news", "财经商业"),
                Map.entry("/arstechnica/latest", "科技资讯"), Map.entry("/ifanr/latest", "科技资讯"),
                Map.entry("/geekpark/news", "科技资讯"));
        var configs = given().get("/index/api/routes").then().statusCode(200).extract().jsonPath();
        sources.forEach((path, category) -> {
            assertEquals(category, runtime.routeRegistry().categories().get(path), path);
            assertNotNull(runtime.routeRegistry().resolve(path).handler());
            var route = runtime.routeRegistry().routes().stream()
                    .filter(candidate -> candidate.path().equals(path)).findFirst().orElseThrow();
            assertTrue(route.recommendedScheduleMinutes() > 0, path);
            assertTrue(RouteConfig.defaults(path).isSchedulable(), path);
            assertEquals(false, configs.getBoolean("find { it.path == '" + path + "' }.enabled"));
            assertEquals(0, configs.getInt("find { it.path == '" + path + "' }.scheduleMinutes"));
        });
        String html = given().get("/index").then().statusCode(200).extract().asString();
        sources.values().stream().distinct().forEach(category -> assertTrue(html.contains(category), category));
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

        var about = Jsoup.parse(html).selectFirst(".about-body");
        assertNotNull(about, "A source with a blurb shows it in the editor");
        assertTrue(about.selectFirst("p").text().startsWith("mritd 是国内知名"));
        assertEquals(List.of("领域", "内容"), about.select("th").eachText());
        assertEquals(List.of("Kubernetes", "K8s 集群配置、调优与运维"), about.select("tbody tr").first().select("td").eachText());
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
    void assetsRevalidateWithEtagAndArriveCompressed() {
        String etag = given().when().get("/reader.css").then()
                .statusCode(200)
                .header("Cache-Control", "no-cache")
                .extract().header("ETag");

        given().header("If-None-Match", etag).when().get("/reader.css").then().statusCode(304);
        given().header("Accept-Encoding", "gzip").when().get("/alpine.min.js").then()
                .statusCode(200)
                .header("Content-Encoding", "gzip");
        given().header("Accept-Encoding", "gzip").when().get("/reader").then()
                .statusCode(200)
                .header("Content-Encoding", "gzip");
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

    @Test
    void templateRoutesCannotBeToggledOrFetchedButTheirMountsCan() {
        assertTrue(runtime.isTemplate(runtime.routeConfigStore().get("/rss")));
        assertTrue(runtime.isTemplate(runtime.routeConfigStore().get("/hn/:feed")));
        assertFalse(runtime.isTemplate(runtime.routeConfigStore().get("/mritd/blog")));
        assertFalse(runtime.isTemplate(runtime.routeConfigStore().get("/huggingface/blog")));
        assertFalse(runtime.isTemplate(new RouteConfig("/my-feed", "/rss", false, 300, 1800, 0, null,
                "https://example.com/feed.xml", null)));

        for (String endpoint : List.of("/index/routes/toggle", "/index/routes/fetch")) {
            given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
                    .formParam("path", "/hn/:feed").formParam("category", "科技资讯")
                    .when().post(endpoint).then().statusCode(409);
        }
        assertFalse(runtime.routeConfigStore().get("/hn/:feed").enabled());
    }

    @Test
    void everyBuiltInRouteDeclaresATopic() {
        runtime.routeRegistry().routes().forEach(route -> assertNotNull(route.topic(), route.path()));
    }

    @Test
    void routeEditorAssignsAKnownTopicAndRejectsUnknownOnes() {
        given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
                .formParam("path", "/mritd/blog").formParam("sourcePath", "/mritd/blog")
                .formParam("routeCacheTtlSeconds", "300").formParam("detailCacheTtlSeconds", "1800")
                .formParam("category", "人文社会")
                .when().post("/index/routes").then().statusCode(200);
        assertEquals("人文社会", runtime.categoryOf("/mritd/blog"));
        assertTrue(given().get("/index/routes/edit?path=/mritd/blog").then().statusCode(200).extract().asString()
                .contains("继承来源（软件开发）"));

        given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
                .formParam("path", "/mritd/blog").formParam("sourcePath", "/mritd/blog")
                .formParam("routeCacheTtlSeconds", "300").formParam("detailCacheTtlSeconds", "1800")
                .formParam("category", "不存在的分类")
                .when().post("/index/routes").then().statusCode(400);

        given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
                .formParam("path", "/mritd/blog").formParam("sourcePath", "/mritd/blog")
                .formParam("routeCacheTtlSeconds", "300").formParam("detailCacheTtlSeconds", "1800")
                .formParam("category", "")
                .when().post("/index/routes").then().statusCode(200);
        assertEquals("软件开发", runtime.categoryOf("/mritd/blog"));
    }

}
