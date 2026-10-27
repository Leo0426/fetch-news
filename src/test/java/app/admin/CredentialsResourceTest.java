package app.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.AppRuntime;
import app.TestPages;
import app.core.CacheService;
import app.core.CredentialConfig;
import app.core.Feed;
import app.core.FeedItem;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.RouteHandler;
import app.core.Topic;
import app.fetch.FetchScheduler;
import app.routes.bilibili.BilibiliCookieVerifier;
import app.routes.twitter.TwitterCookieVerifier;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CredentialsResourceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void savingANewXCookieRefetchesOnlyTheSubscribedXSources() throws Exception {
        var fetched = new CountDownLatch(1);
        var xFetches = new AtomicInteger();
        var otherFetches = new AtomicInteger();
        RouteHandler x = context -> {
            xFetches.incrementAndGet();
            fetched.countDown();
            return feed();
        };
        RouteHandler other = context -> {
            otherFetches.incrementAndGet();
            return feed();
        };
        var configs = new RouteConfigStore(directory.resolve("routes.json"), mapper);
        var runtime = TestPages.runtime(configs,
                new Route("/x/openai", x, "OpenAI X", Topic.AI, "X", "Timeline", 60),
                new Route("/x/github", x, "GitHub X", Topic.SOFTWARE, "X", "Timeline", 60),
                new Route("/science", other, "Science", Topic.SCIENCE, "RSS", "Feed", 60));
        configs.save(RouteConfig.defaults("/x/openai").withEnabled(true).withScheduleMinutes(60));
        configs.save(RouteConfig.defaults("/science").withEnabled(true).withScheduleMinutes(60));
        runtime.credentialConfigStore().save(CredentialConfig.defaults());
        try (var store = new FeedStore(directory.resolve("feeds.db"), mapper)) {
            var scheduler = new FetchScheduler(runtime, store, null, null, null);
            try {
                MockMvc mvc = mvc(runtime, scheduler);
                String page = mvc.perform(post("/index/credentials")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("twitterCookie", "auth_token=a; ct0=b"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

                assertTrue(page.contains("正在重新抓取 1 个"), page);
                assertTrue(fetched.await(5, TimeUnit.SECONDS));
                assertEquals("auth_token=a; ct0=b", runtime.credentialConfigStore().getStored().twitterCookie());

                String unchanged = mvc.perform(post("/index/credentials")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("twitterCookie", "auth_token=a; ct0=b"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertTrue(Jsoup.parse(unchanged).text().contains("已保存"));
                assertFalse(unchanged.contains("重新抓取"), unchanged);
            } finally {
                scheduler.shutdown();
            }
        }
        assertEquals(1, xFetches.get());
        assertEquals(0, otherFetches.get());
    }

    @Test
    void verifyChecksTheFormCookiesWithoutSavingThem() throws Exception {
        var configs = new RouteConfigStore(directory.resolve("routes.json"), mapper);
        var runtime = TestPages.runtime(configs);
        runtime.credentialConfigStore().save(CredentialConfig.defaults());
        try (var store = new FeedStore(directory.resolve("feeds.db"), mapper)) {
            var scheduler = new FetchScheduler(runtime, store, null, null, null);
            try {
                String html = mvc(runtime, scheduler).perform(post("/index/credentials/verify")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("twitterCookie", "auth_token=a")
                                .param("bilibiliCookie", "buvid3=x; buvid4=y"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

                var rows = Jsoup.parse(html).select(".credential-check-row");
                assertEquals(2, rows.size());
                assertTrue(rows.get(0).text().contains("X 登录 Cookie"), rows.get(0).text());
                assertTrue(rows.get(0).text().contains("ct0"), rows.get(0).text());
                assertTrue(rows.get(1).text().contains("格式正确"), rows.get(1).text());
                assertEquals("", runtime.credentialConfigStore().getStored().twitterCookie());

                String empty = mvc(runtime, scheduler).perform(post("/index/credentials/verify")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                        .andReturn().getResponse().getContentAsString();
                assertTrue(empty.contains("没有可验证的 Cookie"), empty);
            } finally {
                scheduler.shutdown();
            }
        }
    }

    private MockMvc mvc(AppRuntime runtime, FetchScheduler scheduler) {
        var resource = new CredentialsResource(runtime, scheduler, TestPages.engine(),
                new TwitterCookieVerifier(url -> "", mapper, new CacheService()),
                new BilibiliCookieVerifier(url -> "", mapper));
        return MockMvcBuilders.standaloneSetup(resource).build();
    }

    private static Feed feed() {
        return new Feed("Feed", "https://example.test", "Feed", List.of(
                new FeedItem("Post", "https://example.test/post", "Body", null, null, List.of())));
    }
}
