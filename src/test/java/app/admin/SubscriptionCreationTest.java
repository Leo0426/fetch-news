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
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.fetch.FetchScheduler;
import app.routes.GenericRssRoute;
import app.store.FeedStore;
import app.subscriptions.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SubscriptionCreationTest {
    @TempDir Path directory;

    @Test void emptyDiscoveryOffersRssCreationWithoutWritingOrFetching() throws Exception {
        try (var fixture = new Fixture()) {
            String html = fixture.mvc.perform(get("/index/subscriptions").param("q", "no match"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertNotNull(Jsoup.parse(html).selectFirst(".subscriptions-header [hx-get='/index/subscriptions/new']"));
            assertNotNull(Jsoup.parse(html).selectFirst(".subscriptions-empty [hx-get='/index/subscriptions/new']"));
            String form = fixture.mvc.perform(get("/index/subscriptions/new"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertNotNull(Jsoup.parse(form).selectFirst("form[hx-post='/index/subscriptions/create'] input[name='name']"));
            assertNotNull(Jsoup.parse(form).selectFirst("input[name='feedUrl']"));
            assertFalse(Files.exists(fixture.configFile));
            assertEquals(0, fixture.fetches.get());
        }
    }

    @Test void namedRssMountCanBeSubscribedAndReadWithoutChangingExistingSettings() throws Exception {
        try (var fixture = new Fixture()) {
            var existing = new RouteConfig("/keep", "/rss", false, 900, 3600, 120,
                    "0 0 8 * * *", "https://example.test/keep.xml", "原有订阅");
            fixture.configs.save(existing);
            var response = fixture.mvc.perform(post("/index/subscriptions/create")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("name", "  我的科学  ").param("feedUrl", "  https://example.test/science.xml  "))
                    .andExpect(status().isCreated()).andReturn().getResponse();
            var card = Jsoup.parse(response.getContentAsString()).selectFirst(".subscription-card");
            assertNotNull(card);
            assertTrue(card.text().contains("我的科学"));
            assertTrue(card.text().contains("订阅并抓取"));
            String path = card.attr("data-path");
            RouteConfig created = fixture.configs.get(path);
            assertEquals("/rss", created.sourcePath());
            assertEquals("https://example.test/science.xml", created.feedUrl());
            assertFalse(created.enabled());
            assertFalse(created.hasSchedule());
            assertEquals(existing, fixture.configs.get("/keep"));
            assertEquals(0, fixture.fetches.get());
            assertEquals("#subscriptions-view", response.getHeader("HX-Retarget"));
            assertTrue(response.getHeader("HX-Trigger-After-Swap").contains(card.id()));
            assertEquals(created, new RouteConfigStore(fixture.configFile, fixture.mapper).get(path));

            fixture.mvc.perform(post("/index/subscriptions/start").param("path", path).param("scheduleMinutes", "180"))
                    .andExpect(status().isOk());
            fixture.scheduler.shutdown();
            String saved = fixture.mvc.perform(get("/index/subscriptions/card").param("path", path))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertNotNull(Jsoup.parse(saved).selectFirst("a.subscription-read"));
            assertEquals(1, fixture.store.unread().loadUnreadPage(UserAccount.OWNER_ID, path, "", false, null, 50).totalCount());
            assertEquals(1, fixture.fetches.get());
            assertEquals(existing, fixture.configs.get("/keep"));
        }
    }

    @Test void invalidDraftsRetainBothFieldsAndDoNotCreateSubscriptions() throws Exception {
        try (var fixture = new Fixture()) {
            for (String url : List.of("", "/feed.xml", "ftp://example.test/feed", "https://user:secret@example.test/feed", "https://")) {
                String html = fixture.mvc.perform(post("/index/subscriptions/create")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("name", "草稿 & 名称").param("feedUrl", url))
                        .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
                var draft = Jsoup.parse(html);
                assertEquals("草稿 & 名称", draft.selectFirst("input[name='name']").val());
                assertEquals(url, draft.selectFirst("input[name='feedUrl']").val());
                assertFalse(draft.selectFirst("[role='alert']").text().isBlank());
            }
            fixture.mvc.perform(post("/index/subscriptions/create").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("name", "  ")
                            .param("feedUrl", "https://example.test/feed.xml"))
                    .andExpect(status().isUnprocessableEntity());
            assertFalse(Files.exists(fixture.configFile));
            assertEquals(0, fixture.fetches.get());
        }
    }

    @Test void duplicateNameOrFeedCannotOverwriteOrCreateAnotherSubscription() throws Exception {
        try (var fixture = new Fixture()) {
            var existing = new RouteConfig("/science", "/rss", true, 900, 3600, 180,
                    "0 0 8 * * *", "https://example.test/science.xml", "科学");
            fixture.configs.save(existing);
            String saved = Files.readString(fixture.configFile);
            fixture.mvc.perform(post("/index/subscriptions/create").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("name", "科学")
                            .param("feedUrl", "https://example.test/different.xml"))
                    .andExpect(status().isUnprocessableEntity());
            fixture.mvc.perform(post("/index/subscriptions/create").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("name", "另一个名称")
                            .param("feedUrl", "https://example.test/science.xml"))
                    .andExpect(status().isUnprocessableEntity());
            assertEquals(saved, Files.readString(fixture.configFile));
            assertEquals(existing, fixture.configs.get("/science"));
            assertEquals(0, fixture.fetches.get());
        }
    }

    @Test void failedSavePreservesDraftWithoutClaimingItWasSaved() throws Exception {
        try (var fixture = new Fixture()) {
            Files.createDirectory(fixture.configFile);
            String html = fixture.mvc.perform(post("/index/subscriptions/create")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("name", "科学").param("feedUrl", "https://example.test/science.xml"))
                    .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
            var draft = Jsoup.parse(html);
            assertEquals("科学", draft.selectFirst("input[name='name']").val());
            assertEquals("https://example.test/science.xml", draft.selectFirst("input[name='feedUrl']").val());
            assertNotNull(draft.selectFirst("[role='alert']"));
            assertFalse(html.contains(directory.toString()));
            assertEquals(1, fixture.configs.list(List.of("/rss")).size());
        }
    }

    private final class Fixture implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper();
        final Path configFile = directory.resolve("routes.json");
        final AtomicInteger fetches = new AtomicInteger();
        final RouteConfigStore configs = new RouteConfigStore(configFile, mapper);
        final FeedStore store;
        final FetchScheduler scheduler;
        final MockMvc mvc;

        Fixture() throws Exception {
            store = new FeedStore(directory.resolve("feed.db"), mapper);
            var route = new Route("/rss", new GenericRssRoute(url -> {
                fetches.incrementAndGet();
                assertEquals("https://example.test/science.xml", url);
                return """
                        <rss version="2.0"><channel><title>Science</title><link>https://example.test</link>
                        <description>Research</description><item><title>A discovery</title>
                        <link>https://example.test/article</link><description>Research article</description>
                        </item></channel></rss>
                        """;
            }, store), "RSS");
            var runtime = TestPages.runtime(configs, route);
            scheduler = new FetchScheduler(runtime, store, null, null, null);
            var engine = TestPages.engine();
            mvc = MockMvcBuilders.standaloneSetup(new SubscriptionResource(
                    new SubscriptionService(runtime, store, scheduler), engine)).build();
        }

        @Override public void close() throws Exception {
            scheduler.shutdown();
            store.close();
        }
    }
}
