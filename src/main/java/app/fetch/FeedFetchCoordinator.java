package app.fetch;

import app.core.Feed;
import app.core.RouteContext;
import app.core.RouteHandler;
import app.core.RouteMatch;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class FeedFetchCoordinator {
    private final ConcurrentHashMap<FetchKey, CompletableFuture<Feed>> inFlight = new ConcurrentHashMap<>();

    public Feed fetch(RouteMatch match, RouteContext context, Callable<Feed> loader) throws Exception {
        FetchKey key = new FetchKey(match.handler(), context);
        CompletableFuture<Feed> pending = new CompletableFuture<>();
        CompletableFuture<Feed> existing = inFlight.putIfAbsent(key, pending);
        if (existing != null) return await(existing);

        Feed feed = null;
        Throwable failure = null;
        try {
            feed = loader.call();
            return feed;
        } catch (Exception | Error caught) {
            failure = caught;
            throw caught;
        } finally {
            inFlight.remove(key, pending);
            if (failure == null) {
                pending.complete(feed);
            } else {
                // get() unwraps this layer instead of changing the loader's original exception.
                pending.completeExceptionally(new CompletionException(failure));
            }
        }
    }

    private static Feed await(CompletableFuture<Feed> pending) throws Exception {
        try {
            return pending.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    private record FetchKey(RouteHandler handler, RouteContext context) {}
}
