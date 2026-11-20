package app.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.auth.AuthTokens;
import app.auth.Role;
import app.auth.UserAccount;
import app.core.FeedItem;
import app.store.FeedStore;
import app.store.PickKind;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"fetch-news.auth.enabled=true", "fetch-news.admin-token=test-admin-token-0123456789"})
class AdminUserContentTest {
    @LocalServerPort
    int port;

    @Autowired
    FeedStore store;

    @Test
    void administratorCanViewAnIndividualDisabledReadersBookmarks() throws Exception {
        UserAccount account = member();
        store.reading().saveBookmark(account.id(), "https://example.com/old", "Archived bookmark", "/news");
        store.reading().saveBookmark(member().id(), "https://example.com/private", "Other bookmark", "/news");
        store.accounts().setUserDisabled(account.id(), true);

        admin().get(path(account)).then().statusCode(200).header("Cache-Control", containsString("no-store"))
                .body(containsString(account.name()), containsString("已停用"), containsString("Archived bookmark"),
                        not(containsString("Other bookmark")));
    }

    @Test
    void readHistoryPagesIncludeEveryStoredLinkEvenAfterAnArticleIsGone() throws Exception {
        UserAccount account = member();
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 21; i++) {
            String link = "https://example.com/history/" + i;
            store.reading().markRead(account.id(), link);
            expected.add(link);
        }
        var first = Jsoup.parse(admin().queryParam("tab", "read").get(path(account)).asString());
        String next = first.selectFirst(".pager button:contains(下一页)").attr("hx-get");
        assertEquals(path(account) + "?tab=read&page=2", next);
        var second = Jsoup.parse(admin().get(next).then().statusCode(200).extract().asString());
        var titles = first.select("[data-content-row] .user-content-title").eachText();
        assertEquals(20, titles.size());
        Set<String> seen = new HashSet<>(titles);
        assertTrue(seen.addAll(second.select("[data-content-row] .user-content-title").eachText()));
        assertEquals(expected, seen);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bookmarks", "read"})
    void adminAddsAStoredArticleAndRemovesOnlyTheTargetAccountsRecord(String tab) throws Exception {
        UserAccount target = member();
        UserAccount other = member();
        String link = "https://example.com/item/" + UUID.randomUUID();
        store.saveItems("/news", List.of(new FeedItem("Saved article", link, "Body", Instant.now(), null, List.of())));
        store.reading().saveBookmark(other.id(), link, "Saved article", "/news");
        store.reading().markRead(other.id(), link);
        store.accounts().setUserDisabled(target.id(), true);

        String endpoint = path(target) + "/" + tab;
        admin().formParam("action", "add").formParam("link", link).post(endpoint).then().statusCode(200)
                .body(containsString("Saved article"));
        assertTrue(hasRecord(target.id(), tab, link));
        admin().formParam("action", "remove").formParam("link", link).post(endpoint).then().statusCode(200)
                .body(not(containsString("Saved article")));
        assertFalse(hasRecord(target.id(), tab, link));
        assertTrue(hasRecord(other.id(), tab, link));
    }

    @Test
    void correctingAndClearingFeedbackPreservesOtherAccountsAndTheOtherLabel() throws Exception {
        UserAccount target = member();
        UserAccount other = member();
        String link = "https://example.com/votes";
        store.picks().setFeedback(target.id(), link, PickKind.MUST_KNOW, "up", Instant.now());
        store.picks().setFeedback(target.id(), link, PickKind.QUALITY, "down", Instant.now());
        store.picks().setFeedback(other.id(), link, PickKind.QUALITY, "down", Instant.now());

        String endpoint = path(target) + "/feedback";
        admin().formParam("action", "up").formParam("link", link).formParam("kind", "quality")
                .post(endpoint).then().statusCode(200).body(containsString("精选 · 准"));
        assertEquals("up", store.picks().feedback(target.id(), List.of(link)).get(link).quality());
        admin().formParam("action", "remove").formParam("link", link).formParam("kind", "quality")
                .post(endpoint).then().statusCode(200).body(not(containsString("精选 · 准")));
        var remaining = store.picks().feedback(target.id(), List.of(link)).get(link);
        assertEquals("up", remaining.mustKnow());
        assertNull(remaining.quality());
        assertEquals("down", store.picks().feedback(other.id(), List.of(link)).get(link).quality());
        admin().formParam("action", "up").formParam("link", link).formParam("kind", "quality")
                .post(endpoint).then().statusCode(200).body(containsString("这条反馈已不存在"));
        assertNull(store.picks().feedback(target.id(), List.of(link)).get(link).quality());
    }

    @Test
    void memberCannotViewOrEditAnotherAccountThroughTheAdminInterface() throws Exception {
        UserAccount target = member();
        UserAccount member = member();
        String link = "https://example.com/private";
        store.reading().markRead(target.id(), link);

        given().port(port).header("Authorization", "Bearer " + member.name())
                .get(path(target)).then().statusCode(403);
        given().port(port).header("Authorization", "Bearer " + member.name())
                .formParam("action", "remove").formParam("link", link)
                .post(path(target) + "/read").then().statusCode(403);
        assertTrue(store.reading().isRead(target.id(), link));
    }

    @Test
    void addingUnknownArticleOrWritingDeletedAccountCannotCreateRecords() throws Exception {
        UserAccount target = member();
        String missing = "https://example.com/missing";
        admin().formParam("action", "add").formParam("link", missing)
                .post(path(target) + "/read").then().statusCode(200).body(containsString("未找到这篇文章"));
        assertFalse(store.reading().isRead(target.id(), missing));
        store.accounts().deleteUser(target.id());
        admin().get(path(target)).then().statusCode(404);
        admin().formParam("action", "add").formParam("link", missing)
                .post(path(target) + "/read").then().statusCode(404);
        assertFalse(store.reading().isRead(target.id(), missing));
    }

    private boolean hasRecord(long id, String tab, String link) throws Exception {
        return tab.equals("read") ? store.reading().isRead(id, link) : store.reading().isBookmarked(id, link);
    }

    private UserAccount member() throws Exception {
        String name = UUID.randomUUID().toString();
        return store.accounts().createUser(name, Role.MEMBER, AuthTokens.hash(name), AuthTokens.newFeedKey());
    }

    private RequestSpecification admin() {
        return given().port(port).header("Authorization", "Bearer test-admin-token-0123456789");
    }

    private static String path(UserAccount account) {
        return "/index/users/" + account.id() + "/content";
    }
}
