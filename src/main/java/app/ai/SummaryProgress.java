package app.ai;

public record SummaryProgress(String state, String summary, String error) {
    public boolean pending() {
        return "queued".equals(state) || "running".equals(state);
    }
}
