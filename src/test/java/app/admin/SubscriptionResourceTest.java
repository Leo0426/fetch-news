package app.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.TestPages;
import app.auth.UserAccount;
import app.core.Feed;
import app.core.FeedItem;
import app.core.Route;
import app.core.RouteConfigStore;
import app.core.Topic;
import app.fetch.FetchScheduler;
import app.store.FeedStore;
import app.subscriptions.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SubscriptionResourceTest {
    @TempDir Path directory;

    @Test void subscriptionReturnsPollingCardThenSavedArticlesAndReadingLink() throws Exception {
        var mapper = new ObjectMapper();
        var release = new CountDownLatch(1);
        Route route = new Route("/science", context -> {
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new Feed("Science", "https://example.test", "Research", List.of(
                    new FeedItem("Research", "https://example.test/article", "Content", null, null, List.of())));
        }, "Science · First-hand research", Topic.SCIENCE, "RSS", "Feed", 180);
        var configs = new RouteConfigStore(directory.resolve("routes.json"), mapper);
        var runtime = TestPages.runtime(configs, route);
        try (var store = new FeedStore(directory.resolve("feeds.db"), mapper)) {
            var scheduler = new FetchScheduler(runtime, store, null, null, null);
            var service = new SubscriptionService(runtime, store, scheduler);
            var engine = TestPages.engine();
            var mvc = MockMvcBuilders.standaloneSetup(new SubscriptionResource(service, engine)).build();
            try {
                String page = mvc.perform(get("/index/subscriptions").param("category", "科学"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertTrue(Jsoup.parse(page).text().contains("First-hand research"));
                assertFalse(Files.exists(directory.resolve("routes.json")));
                mvc.perform(post("/index/subscriptions/start").param("path", "/missing"))
                        .andExpect(status().isNotFound());
                mvc.perform(post("/index/subscriptions/start").param("path", "/science").param("scheduleMinutes", "0"))
                        .andExpect(status().isBadRequest());
                String running = mvc.perform(post("/index/subscriptions/start").param("path", "/science")
                                .param("scheduleMinutes", "360"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertTrue(Jsoup.parse(running).selectFirst(".subscription-card").attr("hx-trigger").contains("every 2s"));
                assertEquals(360, configs.get("/science").scheduleMinutes());
                assertTrue(mvc.perform(get("/index/subscriptions/card").param("path", "/science"))
                        .andReturn().getResponse().getContentAsString().contains("正在抓取"));
            } finally {
                release.countDown();
                scheduler.shutdown();
            }
            String completed = mvc.perform(get("/index/subscriptions/card").param("path", "/science"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var card = Jsoup.parse(completed);
            assertTrue(card.text().contains("更新完成"));
            assertFalse(card.selectFirst(".subscription-card").attr("hx-trigger").contains("every"));
            assertNotNull(card.selectFirst("a[href='/reader/unread?source=%2Fscience']"));
            assertEquals(1, store.unread().loadUnreadPage(UserAccount.OWNER_ID, "/science", "", false, null, 50).totalCount());
            mvc.perform(post("/index/subscriptions/pause").param("path", "/science")).andExpect(status().isOk());
            assertFalse(configs.get("/science").enabled());
            assertEquals(1, store.items().countItems("/science"));
            mvc.perform(post("/index/subscriptions/refresh").param("path", "/science"))
                    .andExpect(status().isBadRequest());
        }
    }
}
