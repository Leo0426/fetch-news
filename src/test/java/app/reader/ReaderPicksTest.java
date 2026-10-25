package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.AppRuntime;
import app.TestPages;
import app.core.FeedItem;
import app.core.Route;
import app.core.RouteConfig;
import app.core.RouteConfigStore;
import app.core.Topic;
import app.store.FeedStore;
import app.store.ItemPick;
import app.store.PickKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReaderPicksTest {
    private static final String MUST = "https://example.com/must";
    private static final String PLAIN = "https://example.com/plain";

    @TempDir Path directory;
    private FeedStore store;
    private ReaderResource resource;
    private RouteConfigStore configs;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        store = new FeedStore(directory.resolve("picks.db"), mapper);
        configs = new RouteConfigStore(directory.resolve("routes.json"), mapper);
        configs.save(new RouteConfig("/news", "/news", true, 300, 1800, 0, null, null, null));
        AppRuntime runtime = TestPages.runtime(configs, new Route("/news", context -> { throw new AssertionError("No upstream calls expected"); },
                        "News", Topic.NEWS));
        var engine = TestPages.engine();
        resource = new ReaderResource(store, runtime, new ReaderTemplateRenderer(engine, runtime),
                null, null);
        store.saveItems("/news", List.of(
                new FeedItem("Big event", MUST, "<p>Body</p>", null, null, List.of()),
                new FeedItem("Ordinary", PLAIN, "<p>Body</p>", null, null, List.of())));
        store.picks().saveTriage("时事要闻", List.of(MUST, PLAIN), Map.of(MUST, new ItemPick("影响面广", null)),
                "fixture", Instant.now());
    }

    @AfterEach
    void closeStore() throws Exception {
        store.close();
    }

    @Test
    void rendersLabelsWithReasonsAndRecordsTheReadersVerdict() throws Exception {
        Document page = Jsoup.parse(resource.reader(24));
        var card = page.selectFirst(".rd-card[data-link=" + MUST + "]");
        assertEquals("must ", card.attr("data-picks"));
        assertEquals("", card.attr("data-verdict-must"));
        assertEquals("影响面广", card.select(".rd-pick-reason").text());
        assertEquals(2, card.select(".rd-pick-vote").size());
        assertTrue(page.select(".rd-card[data-link=" + PLAIN + "] .rd-picks").isEmpty());
        assertTrue(page.select(".reader-pick-accuracy").isEmpty(), "no accuracy line before any feedback");

        assertEquals("down", resource.setPickFeedback(MUST, "must", "down").getBody());

        page = Jsoup.parse(resource.reader(24));
        assertEquals("down", page.selectFirst(".rd-card[data-link=" + MUST + "]").attr("data-verdict-must"));
        assertEquals("近 30 天你反馈 1 条，准确 0%", page.select(".reader-pick-accuracy").text());
        assertTrue(store.picks().rejected(List.of(PLAIN), PickKind.MUST_KNOW).isEmpty());
        assertEquals(1, store.picks().rejected(List.of(MUST), PickKind.MUST_KNOW).size());

        assertEquals("none", resource.setPickFeedback(MUST, "must", "none").getBody());
        assertEquals("", Jsoup.parse(resource.reader(24)).selectFirst(".rd-card[data-link=" + MUST + "]")
                .attr("data-verdict-must"));
    }

    @Test
    void rejectsFeedbackOnLabelsTheArticleDoesNotCarry() {
        assertEquals(404, resource.setPickFeedback(MUST, "quality", "up").getStatusCode().value());
        assertEquals(404, resource.setPickFeedback(PLAIN, "must", "up").getStatusCode().value());
        assertEquals(400, resource.setPickFeedback(MUST, "other", "up").getStatusCode().value());
        assertEquals(400, resource.setPickFeedback(MUST, "must", "maybe").getStatusCode().value());
    }

    @Test
    void readerGroupsARouteUnderItsOwnTopicWhenOneIsSet() throws Exception {
        assertEquals("时事要闻", Jsoup.parse(resource.reader(24)).select(".reader-source-tag").text());

        configs.save(configs.get("/news").withEnabled(true));
        configs.save(new RouteConfig("/news", "/news", true, 300, 1800, 0, null, null, null, false, "科学"));

        assertEquals("科学", Jsoup.parse(resource.reader(24)).select(".reader-source-tag").text());
    }
}
