package app.radar;

import app.ai.AiConfig;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

public final class RadarTypes {
    private RadarTypes() {}

    public record RunRequest(String date, String zoneId, String idempotencyKey, AiConfig ai, Map<String,String> categories) {
        public RunRequest {
            LocalDate.parse(date);
            ZoneId.of(zoneId);
            if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200)
                throw new IllegalArgumentException("A request key of 1–200 characters is required");
            if (ai == null) throw new IllegalArgumentException("Model identity is required");
            categories = Map.copyOf(categories);
        }
    }
    public record Candidate(String link, String title, String description, String author, String pubDate,
                            long firstSeenAt, List<String> sources) {
        public Candidate { sources = List.copyOf(sources); }
    }
    public record Analysis(String link, String profile, String category, Double score, String reason, String summary,
                           List<String> tags, boolean limitedInput, String cacheKey, String error) {
        public Analysis { tags = List.copyOf(tags); }
    }
    public record Event(String id, Analysis primary, List<Analysis> related) {
        public Event { related = List.copyOf(related); }
    }
    public record Decision(String link, String reason, String eventId) {}
    public record Selection(List<Event> selected, List<Decision> decisions) {
        public Selection { selected = List.copyOf(selected); decisions = List.copyOf(decisions); }
    }
    public record ReportStory(Event event, Map<String,List<String>> blocks, String error) {
        public ReportStory { blocks = Map.copyOf(blocks); }
    }
    public record Report(String runId, String date, String zoneId, List<ReportStory> stories, List<Decision> decisions,
                         int totalCandidates, int omitted, int failures, Map<String,String> diagnostics) {
        public Report { stories = List.copyOf(stories); decisions = List.copyOf(decisions); diagnostics = Map.copyOf(diagnostics); }
    }
    public record RunStatus(String runId, String date, int version, String state, String stage,
                            int totalCandidates, int omitted, int calls, String error) {}
    public record RunSnapshot(RunStatus status, RunRequest request, RadarConfig config, Map<String,Profile> profiles,
                              List<Candidate> candidates, long createdAt, Map<String,String> checkpoints) {
        public RunSnapshot { profiles = Map.copyOf(profiles); candidates = List.copyOf(candidates); checkpoints = Map.copyOf(checkpoints); }
    }
}
