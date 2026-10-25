package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.core.FeedItem;
import app.store.FeedStore;
import app.store.ItemPick;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArticleTriageTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ArticleTriage.Routes ROUTES = new ArticleTriage.Routes() {
        @Override public String category(String routePath) {
            return routePath.startsWith("/tech") ? "科技" : "新闻";
        }

        @Override public String sourceLabel(String routePath) {
            return routePath;
        }
    };

    @TempDir Path directory;

    @Test
    void parsesFencedJsonAndKeepsAtMostThreeValidPicks() {
        var triage = triage(null, prompt -> "", Instant.now());
        var batch = List.of(candidate("a"), candidate("b"), candidate("c"), candidate("d"), candidate("e"));
        var picks = triage.parsePicks("""
                ```json
                {"mustKnow":[{"id":9,"reason":"越界"},{"id":1,"reason":" "},{"id":2,"reason":"影响大"},
                             {"id":2,"reason":"重复"},{"id":3,"reason":"三"},{"id":4,"reason":"四"},{"id":5,"reason":"五"}],
                 "quality":[]}
                ```""", batch);

        assertEquals(Map.of(link("b"), "影响大", link("c"), "三", link("d"), "四"), picks.mustKnow());
        assertTrue(picks.quality().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> triage.parsePicks("{\"mustKnow\":{}}", batch));
        assertThrows(IllegalArgumentException.class, () -> triage.parsePicks("no json here", batch));
    }

    @Test
    void labelsOnlyCategoriesWithUnseenArticlesAndReRanksAfterTheQuietPeriod() throws Exception {
        Instant now = Instant.now();
        List<String> prompts = new ArrayList<>();
        try (FeedStore store = new FeedStore(directory.resolve("feed.db"), MAPPER)) {
            store.saveItems("/tech", List.of(item("t1"), item("t2")));
            store.saveItems("/news", List.of(item("n1")));
            var answer = new String[]{"""
                    {"mustKnow":[{"id":1,"reason":"重大发布"}],"quality":[{"id":1,"reason":"深度分析"}]}"""};
            var clock = new MutableClock(now);
            var triage = new ArticleTriage(model(prompt -> {
                prompts.add(prompt);
                return answer[0];
            }), store, aiConfig(true), ROUTES, MAPPER, clock);

            triage.runDue();

            assertEquals(2, prompts.size());
            List<String> all = List.of(link("t1"), link("t2"), link("n1"));
            assertEquals(2, store.picks().triagedLinks(List.of(link("t1"), link("t2")), "科技").size());
            assertEquals(1, store.picks().triagedLinks(List.of(link("n1")), "新闻").size());
            Map<String, ItemPick> picks = store.picks().labels(all);
            assertEquals(2, picks.size());
            ItemPick first = picks.values().iterator().next();
            assertEquals("重大发布", first.mustKnowReason());
            assertEquals("深度分析", first.qualityReason());

            triage.runDue();
            assertEquals(2, prompts.size(), "fully triaged categories are skipped");

            store.saveItems("/tech", List.of(item("t3")));
            triage.runDue();
            assertEquals(2, prompts.size(), "a category waits for the quiet period");

            clock.now = now.plus(ArticleTriage.MIN_INTERVAL).plusSeconds(1);
            answer[0] = "{\"mustKnow\":[],\"quality\":[]}";
            triage.runDue();
            assertEquals(3, prompts.size());
            assertTrue(prompts.getLast().contains("科技"));
            assertTrue(store.picks().labels(List.of(link("t1"), link("t2"), link("t3"))).isEmpty(),
                    "re-ranking replaces earlier labels in the window");
        }
    }

    @Test
    void largeCategoriesRunATournamentAndLabelTheFinalPicks() throws Exception {
        List<String> prompts = new ArrayList<>();
        try (FeedStore store = new FeedStore(directory.resolve("feed.db"), MAPPER)) {
            List<FeedItem> items = new ArrayList<>();
            for (int i = 0; i < ArticleTriage.BATCH_SIZE + 5; i++) items.add(item("x" + i));
            store.saveItems("/news", items);
            var triage = triage(store, prompt -> {
                prompts.add(prompt);
                // Every round nominates its first article; the final round sees only the nominees.
                return "{\"mustKnow\":[{\"id\":1,\"reason\":\"第" + prompts.size() + "轮\"}],\"quality\":[]}";
            }, Instant.now());

            triage.runDue();

            assertEquals(3, prompts.size());
            assertEquals(2, countArticles(prompts.getLast()));
            Map<String, ItemPick> picks = store.picks().labels(items.stream().map(FeedItem::link).toList());
            assertEquals(1, picks.size());
            assertEquals("第3轮", picks.values().iterator().next().mustKnowReason());
        }
    }

    @Test
    void reTriagesArticlesWhoseCategoryChanged() throws Exception {
        List<String> prompts = new ArrayList<>();
        try (FeedStore store = new FeedStore(directory.resolve("feed.db"), MAPPER)) {
            store.saveItems("/news", List.of(item("n1")));
            store.picks().saveTriage("旧分类", List.of(link("n1")), Map.of(link("n1"), new ItemPick("旧理由", null)),
                    "fixture", Instant.now());
            var triage = triage(store, prompt -> {
                prompts.add(prompt);
                return "{\"mustKnow\":[],\"quality\":[]}";
            }, Instant.now());

            triage.runDue();

            assertEquals(1, prompts.size());
            assertTrue(store.picks().labels(List.of(link("n1"))).isEmpty(), "labels from the old category are replaced");
        }
    }

    @Test
    void showsReaderTasteToTheModelAndNeverReturnsARejectedLabel() throws Exception {
        List<String> prompts = new ArrayList<>();
        try (FeedStore store = new FeedStore(directory.resolve("feed.db"), MAPPER)) {
            Instant now = Instant.now();
            store.saveItems("/news", List.of(item("n1"), item("n2")));
            store.picks().saveTriage("新闻", List.of(link("n1"), link("n2")),
                    Map.of(link("n1"), new ItemPick("旧理由", null)), "fixture", now.minusSeconds(3600));
            store.picks().setFeedback(1, link("n1"), app.store.PickKind.MUST_KNOW, "down", now);
            store.saveItems("/news", List.of(item("n3")));
            var triage = triage(store, prompt -> {
                prompts.add(prompt);
                // The model insists on the rejected article.
                return "{\"mustKnow\":[{\"id\":" + idOf(prompt, "Title n1") + ",\"reason\":\"还是它\"}],\"quality\":[]}";
            }, now);

            triage.runDue();

            assertEquals(1, prompts.size());
            assertTrue(prompts.getFirst().contains("读者认为不该入选的必知：「Title n1」"));
            assertTrue(store.picks().labels(List.of(link("n1"), link("n2"), link("n3"))).isEmpty());
        }
    }

    private static int idOf(String prompt, String title) {
        try {
            for (var article : MAPPER.readTree(prompt.substring(prompt.indexOf("文章：") + 3).strip())) {
                if (article.path("title").asText().equals(title)) return article.path("id").asInt();
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("article not in prompt: " + title);
    }

    @Test
    void doesNothingWhileAiIsDisabled() throws Exception {
        try (FeedStore store = new FeedStore(directory.resolve("feed.db"), MAPPER)) {
            store.saveItems("/news", List.of(item("n1")));
            var triage = new ArticleTriage(model(prompt -> { throw new AssertionError("AI is off"); }),
                    store, aiConfig(false), ROUTES, MAPPER, Clock.systemUTC());

            triage.runDue();

            assertTrue(store.picks().triagedLinks(List.of(link("n1")), "新闻").isEmpty());
        }
    }

    private ArticleTriage triage(FeedStore store, Function<String, String> answers, Instant now) {
        return new ArticleTriage(model(answers), store, aiConfig(true), ROUTES, MAPPER, Clock.fixed(now, ZoneOffset.UTC));
    }

    private AiConfigStore aiConfig(boolean enabled) {
        var config = new AiConfigStore(directory.resolve("ai.json"), MAPPER);
        config.save(new AiConfig("http://127.0.0.1:1", "fixture", enabled));
        return config;
    }

    private OllamaClient model(Function<String, String> answers) {
        return new OllamaClient(aiConfig(true), MAPPER) {
            @Override public Generation generateJson(String prompt, AiConfig expected) {
                return new Generation(answers.apply(prompt), "fixture");
            }
        };
    }

    private static int countArticles(String prompt) {
        Matcher matcher = Pattern.compile("\"id\":\\d+").matcher(prompt.substring(prompt.indexOf("文章：")));
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static ArticleTriage.Candidate candidate(String id) {
        return new ArticleTriage.Candidate(link(id), "/news", "Title " + id, "", 0);
    }

    private static FeedItem item(String id) {
        return new FeedItem("Title " + id, link(id), "<p>Body " + id + "</p>", null, null, List.of());
    }

    private static String link(String id) {
        return "https://example.com/" + id;
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) { this.now = now; }

        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
