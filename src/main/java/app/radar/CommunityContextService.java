package app.radar;

import app.core.FetchClient;
import app.store.FeedStore;
import app.store.RadarJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public class CommunityContextService implements AutoCloseable {
    private final FeedStore store;
    private final FetchClient client;
    private final ObjectMapper mapper;
    private final AtomicBoolean processing = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("radar-community").factory());

    public CommunityContextService(FeedStore store, FetchClient client, ObjectMapper mapper) throws Exception {
        this.store = store;
        this.client = client;
        this.mapper = mapper;
        if (store != null) store.radarJobs().recoverJobs("community");
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::processNext, 0, 1, TimeUnit.SECONDS);
    }

    public RadarJob submit(String runId, String link, String discussionUrl) throws Exception {
        var report = store.radar().loadReport(runId);
        if (report == null
                || report.stories().stream()
                        .noneMatch(story -> story.event().primary().link().equals(link)
                                || story.event().related().stream()
                                        .anyMatch(related -> related.link().equals(link))))
            throw new IllegalArgumentException("Article is not in the saved report");
        String id = null;
        if (discussionUrl != null && !discussionUrl.isBlank()) id = discussionId(discussionUrl);
        if (id == null) {
            var run = store.radar().loadRun(runId);
            var candidate = run.candidates().stream()
                    .filter(item -> item.link().equals(link))
                    .findFirst()
                    .orElseThrow();
            try {
                id = discussionId(link);
            } catch (IllegalArgumentException ignored) {
            }
            if (id == null)
                for (var anchor : Jsoup.parse(candidate.description() == null ? "" : candidate.description())
                        .select("a[href]")) {
                    try {
                        id = discussionId(anchor.attr("href"));
                        break;
                    } catch (IllegalArgumentException ignored) {
                    }
                }
        }
        if (id == null) throw new IllegalArgumentException("No Hacker News discussion link found; provide its URL");
        String key = RadarJson.hash(List.of(
                link, id, System.currentTimeMillis() / Duration.ofMinutes(30).toMillis()));
        return store.radarJobs()
                .createJob("community", key, mapper.writeValueAsString(Map.of("link", link, "discussionId", id)));
    }

    public CommunityContext cached(String link) throws Exception {
        return store == null ? null : store.radar().loadCommunityContext(link);
    }

    public void processNext() {
        if (store == null || !processing.compareAndSet(false, true)) return;
        RadarJob job = null;
        try {
            job = store.radarJobs().claimJob("community");
            if (job == null) return;
            var data = mapper.readTree(job.payload());
            var context =
                    fetch(data.path("link").asText(), data.path("discussionId").asText());
            store.radar().saveCommunityContext(context);
            store.radarJobs().updateJob(job.id(), "success", job.payload());
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (job != null)
                try {
                    store.radarJobs().updateJob(job.id(), "failed", job.payload());
                } catch (Exception ignored) {
                }
        } finally {
            processing.set(false);
        }
    }

    public CommunityContext fetch(String link, String id) throws Exception {
        if (!id.matches("[1-9][0-9]{0,14}")) throw new IllegalArgumentException("Invalid discussion ID");
        JsonNode story = item(id);
        String discussion = "https://news.ycombinator.com/item?id=" + id;
        if (story.path("deleted").asBoolean()
                || story.path("dead").asBoolean()
                || !story.path("type").asText().equals("story")
                || !story.path("id").asText().equals(id)) throw new IllegalArgumentException("Discussion unavailable");
        if (!sameUrl(link, discussion) && !sameUrl(link, story.path("url").asText()))
            throw new IllegalArgumentException("Discussion does not reference this article");
        List<String> ids = new ArrayList<>();
        for (var kid : story.path("kids")) {
            if (ids.size() >= 10) break;
            if (kid.isIntegralNumber() && kid.asLong() > 0 && !ids.contains(kid.asText())) ids.add(kid.asText());
        }
        List<CommunityContext.Comment> comments = new ArrayList<>();
        LinkedHashSet<String> references = new LinkedHashSet<>();
        var background = Jsoup.parse(story.path("text").asText());
        collectReferences(background, references);
        int failures = 0;
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures =
                    ids.stream().map(child -> threads.submit(() -> item(child))).toList();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    var comment = futures.get(i).get();
                    if (comment.path("deleted").asBoolean()
                            || comment.path("dead").asBoolean()) continue;
                    if (!comment.path("id").asText().equals(ids.get(i))
                            || !comment.path("parent").asText().equals(id)
                            || !comment.path("type").asText().equals("comment")) {
                        failures++;
                        continue;
                    }
                    var document = Jsoup.parse(comment.path("text").asText());
                    collectReferences(document, references);
                    if (!document.text().isBlank())
                        comments.add(new CommunityContext.Comment(
                                RadarJson.truncate(comment.path("by").asText("unknown"), 100),
                                RadarJson.truncate(document.text(), 1500),
                                "https://news.ycombinator.com/item?id=" + ids.get(i)));
                } catch (ExecutionException e) {
                    failures++;
                }
            }
        }
        return new CommunityContext(
                link,
                discussion,
                RadarJson.truncate(Jsoup.parse(story.path("title").asText()).text(), 300),
                RadarJson.truncate(background.text(), 3000),
                comments,
                references.stream().limit(10).toList(),
                System.currentTimeMillis(),
                failures);
    }

    private JsonNode item(String id) throws Exception {
        return mapper.readTree(client.get("https://hacker-news.firebaseio.com/v0/item/" + id + ".json"));
    }

    private void collectReferences(Document document, LinkedHashSet<String> references) {
        for (var anchor : document.select("a[href]")) {
            if (references.size() >= 10) return;
            String url = RadarRenderer.safeUrl(anchor.attr("href"));
            if (!url.isEmpty()) references.add(url);
        }
    }

    public String discussionId(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"news.ycombinator.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || !uri.getPath().equals("/item")
                    || uri.getRawQuery() == null
                    || !uri.getRawQuery().matches("id=[1-9][0-9]{0,14}")) throw new IllegalArgumentException();
            return uri.getRawQuery().substring(3);
        } catch (Exception e) {
            throw new IllegalArgumentException("Expected a Hacker News discussion URL");
        }
    }

    private boolean sameUrl(String left, String right) {
        try {
            if (RadarRenderer.safeUrl(left).isEmpty()
                    || RadarRenderer.safeUrl(right).isEmpty()) return false;
            URI a = URI.create(left), b = URI.create(right);
            return a.getScheme().equalsIgnoreCase(b.getScheme())
                    && a.getHost().equalsIgnoreCase(b.getHost())
                    && a.getPort() == b.getPort()
                    && a.getRawPath().equals(b.getRawPath())
                    && Objects.equals(a.getRawQuery(), b.getRawQuery());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
