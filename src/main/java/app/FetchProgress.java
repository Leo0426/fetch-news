package app;

import app.core.RouteConfig;
import java.time.Instant;

public record FetchProgress(RouteConfig config, State state, Instant startedAt, Instant finishedAt,
                            int itemCount, int newItemCount, String error, Instant retryAt, long logId) {
    public enum State { RUNNING, SUCCEEDED, FAILED, DEFERRED, UNCONFIRMED }

    public FetchProgress(RouteConfig config, State state, Instant startedAt, Instant finishedAt,
                         int itemCount, int newItemCount, String error, Instant retryAt) {
        this(config, state, startedAt, finishedAt, itemCount, newItemCount, error, retryAt, -1);
    }

    public boolean running() {
        return state == State.RUNNING;
    }
}
