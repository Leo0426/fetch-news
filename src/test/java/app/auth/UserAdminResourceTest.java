package app.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.TestPages;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserAdminResourceTest {
    private static final String OWNER_TOKEN = "fixture-test-owner-0123456789";
    private static final String PASSWORD = "fixture-valid-password";
    private static final String USERS = "/index/users";
    @TempDir Path directory;
    private FeedStore store;
    private MockMvc mvc;

    @BeforeEach void setUp() throws Exception {
        store = new FeedStore(directory.resolve("feed.db"), new ObjectMapper());
        var auth = new AuthService(store, true);
        auth.bootstrap(OWNER_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(new UserAdminResource(auth, TestPages.engine()))
                .addFilters(new AuthFilter(auth)).build();
    }

    @AfterEach void close() throws Exception {
        store.close();
    }

    @Test void ownerCreatesPasswordMemberAndCanIssueAndRevokeOneOfTwoAccessTokens() throws Exception {
        String html = admin(post(USERS).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("name", "Member").param("loginMode", "password").param("password", PASSWORD), 200);
        assertFalse(html.contains(PASSWORD));
        assertTrue(Jsoup.parse(html).select("[data-issued-token]").isEmpty());
        var member = store.accounts().listUsers().stream().filter(u -> u.name().equals("Member")).findFirst().orElseThrow();
        String base = USERS + "/" + member.id();
        assertTrue(html.contains(base + "/content"));
        assertFalse(html.contains(member.feedKey()));
        String oldToken = issue(base, "Archive");
        String phoneToken = issue(base, "Phone");
        html = admin(get(base + "/access"), 200);
        assertFalse(html.contains(phoneToken));
        assertFalse(html.contains(oldToken));
        var phone = Jsoup.parse(html).select("[data-account-token]").stream()
                .filter(row -> row.text().contains("Phone")).findFirst().orElseThrow();
        String revoke = phone.select("[hx-delete]").attr("hx-delete");
        assertTrue(revoke.startsWith(base + "/tokens/"));
        var forbidden = mvc.perform(delete(revoke).header("Authorization", "Bearer " + oldToken)).andReturn();
        assertEquals(403, forbidden.getResponse().getStatus());
        admin(delete(revoke), 200);
        assertNotNull(store.accounts().findUserByTokenHash(AuthTokens.hash(oldToken)));
        assertTrue(admin(get(base + "/access"), 200).contains("Archive"));
        assertFalse(admin(get(base + "/access"), 200).contains("Phone"));
        String updated = admin(post(base + "/password").param("password", "changed-password"), 200);
        assertTrue(updated.contains("已重置"));
        assertFalse(updated.contains("changed-password"));
        admin(post(base + "/disabled").param("disabled", "true"), 200);
        assertTrue(admin(get(base + "/access"), 200).contains("已停用"));
        admin(delete(base), 200);
        assertFalse(admin(get(USERS), 200).contains("Member"));
    }

    @Test void oldTokenFormStillWorksButCannotCreateAnotherAdminOrFallBackFromABlankPassword() throws Exception {
        String html = admin(post(USERS).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("name", "Legacy").param("role", "MEMBER"), 200);
        assertTrue(Jsoup.parse(html).select("[data-issued-token]").text().startsWith("fn_"));
        admin(post(USERS).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("name", "AnotherAdmin").param("role", "ADMIN"), 403);
        admin(post(USERS).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("name", "Blank").param("loginMode", "password").param("password", ""), 200);
        assertEquals(2, store.accounts().listUsers().size());
        html = admin(get(USERS + "/1/access"), 200);
        assertTrue(Jsoup.parse(html).select("[hx-post=/index/users/1/password]").isEmpty());
        assertTrue(Jsoup.parse(html).select("[hx-delete=/index/users/1]").isEmpty());
        assertFalse(html.contains(OWNER_TOKEN));
    }

    @Test void membersCannotViewOtherCredentialsAndRevocationIsScopedToTheTargetAccount() throws Exception {
        String first = createLegacy("First");
        String second = createLegacy("Second");
        long firstId = store.accounts().findUserByTokenHash(AuthTokens.hash(first)).id();
        long secondId = store.accounts().findUserByTokenHash(AuthTokens.hash(second)).id();
        String firstBase = USERS + "/" + firstId;
        String secondBase = USERS + "/" + secondId;
        String named = issue(firstBase, "Reader app");
        var denied = mvc.perform(get(firstBase + "/access").header("Authorization", "Bearer " + second))
                .andReturn().getResponse();
        assertEquals(403, denied.getStatus());
        assertFalse(denied.getContentAsString().contains(named));
        var row = Jsoup.parse(admin(get(firstBase + "/access"), 200)).select("[data-account-token]").stream()
                .filter(token -> token.text().contains("Reader app")).findFirst().orElseThrow();
        String path = row.select("[hx-delete]").attr("hx-delete");
        admin(delete(path.replace(firstBase, secondBase)), 200);
        assertNotNull(store.accounts().findUserByTokenHash(AuthTokens.hash(named)));
        assertTrue(admin(get(firstBase + "/access"), 200).contains("Reader app"));
        assertFalse(admin(get(secondBase + "/access"), 200).contains("Reader app"));
    }

    private String createLegacy(String name) throws Exception {
        String html = admin(post(USERS).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("name", name), 200);
        return Jsoup.parse(html).select("[data-issued-token]").text();
    }

    private String issue(String base, String name) throws Exception {
        String html = admin(post(base + "/tokens").param("name", name), 200);
        String token = Jsoup.parse(html).select("[data-issued-token]").text();
        assertTrue(token.startsWith("fn_"));
        return token;
    }

    private String admin(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request.header("Authorization", "Bearer " + OWNER_TOKEN)).andReturn().getResponse();
        assertEquals(status, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        return response.getContentAsString(StandardCharsets.UTF_8);
    }
}
