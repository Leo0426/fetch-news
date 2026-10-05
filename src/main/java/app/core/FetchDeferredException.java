package app.core;

import java.time.Instant;

public final class FetchDeferredException extends RouteException {
    private final Instant retryAt;

    public FetchDeferredException(Instant retryAt) {
        super(RouteError.UPSTREAM_REQUEST_FAILED, "refresh deferred until " + retryAt);
        this.retryAt = retryAt;
    }

    public Instant retryAt() {
        return retryAt;
    }
}
