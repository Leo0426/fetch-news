package app.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import app.core.Feed;
import app.core.RouteContext;
import app.core.RouteError;
import app.core.RouteException;
import app.core.RouteHandler;
import app.core.RouteMatch;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FeedFetchCoordinatorTest {
    @Test
    void concurrentEquivalentRequestsShareOneLoad() throws Exception {
        FeedFetchCoordinator coordinator = new FeedFetchCoordinator();
        Feed feed = new Feed("News", "https://example.com", "News", List.of());
        RouteHandler handler = context -> feed;
        RouteContext context = context("https://example.com/feed.xml");
        RouteMatch match = new RouteMatch(handler, context);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Callable<Feed> loader = () -> {
            calls.incrementAndGet();
            loading.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return feed;
        };

        Task first = start(() -> coordinator.fetch(match, context, loader));
        Task second = null;
        try {
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            second = start(() -> coordinator.fetch(match, context("https://example.com/feed.xml"), loader));
            awaitBlocked(second.thread());
            assertEquals(1, calls.get());
            release.countDown();

            assertSame(feed, first.result().get(5, TimeUnit.SECONDS));
            assertSame(feed, second.result().get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            first.thread().join(5_000);
            if (second != null) second.thread().join(5_000);
        }
    }

    @ParameterizedTest
    @MethodSource("differentContexts")
    void differentContextsLoadIndependently(RouteContext otherContext) throws Exception {
        FeedFetchCoordinator coordinator = new FeedFetchCoordinator();
        Feed firstFeed = new Feed("First", "https://example.com", "", List.of());
        Feed secondFeed = new Feed("Second", "https://example.org", "", List.of());
        RouteContext context = context("https://example.com/feed.xml");
        RouteMatch match = new RouteMatch(ignored -> firstFeed, context);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Task first = start(() -> coordinator.fetch(match, context, () -> {
            loading.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return firstFeed;
        }));
        Task second = null;
        try {
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            second = start(() -> coordinator.fetch(match, otherContext, () -> secondFeed));

            assertSame(secondFeed, second.result().get(5, TimeUnit.SECONDS));
            assertFalse(first.result().isDone());
            release.countDown();
            assertSame(firstFeed, first.result().get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            first.thread().join(5_000);
            if (second != null) second.thread().join(5_000);
        }
    }

    @Test
    void differentHandlersLoadIndependently() throws Exception {
        FeedFetchCoordinator coordinator = new FeedFetchCoordinator();
        Feed firstFeed = new Feed("First", "https://example.com", "", List.of());
        Feed secondFeed = new Feed("Second", "https://example.org", "", List.of());
        RouteContext context = context("https://example.com/feed.xml");
        RouteMatch firstMatch = new RouteMatch(ignored -> firstFeed, context);
        RouteMatch secondMatch = new RouteMatch(ignored -> secondFeed, context);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Task first = start(() -> coordinator.fetch(firstMatch, context, () -> {
            loading.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return firstFeed;
        }));
        Task second = null;
        try {
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            second = start(() -> coordinator.fetch(secondMatch, context, () -> secondFeed));

            assertSame(secondFeed, second.result().get(5, TimeUnit.SECONDS));
            assertFalse(first.result().isDone());
            release.countDown();
            assertSame(firstFeed, first.result().get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            first.thread().join(5_000);
            if (second != null) second.thread().join(5_000);
        }
    }

    @Test
    void completedLoadsAreNotCached() throws Exception {
        FeedFetchCoordinator coordinator = new FeedFetchCoordinator();
        Feed firstFeed = new Feed("First", "https://example.com", "", List.of());
        Feed secondFeed = new Feed("Second", "https://example.com", "", List.of());
        RouteContext context = context("https://example.com/feed.xml");
        RouteMatch match = new RouteMatch(ignored -> firstFeed, context);

        assertSame(firstFeed, coordinator.fetch(match, context, () -> firstFeed));
        assertSame(secondFeed, coordinator.fetch(match, context, () -> secondFeed));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void concurrentFailuresPreserveOriginalExceptionAndAllowRetry(Exception failure) throws Exception {
        FeedFetchCoordinator coordinator = new FeedFetchCoordinator();
        Feed recovered = new Feed("Recovered", "https://example.com", "", List.of());
        RouteContext context = context("https://example.com/feed.xml");
        RouteMatch match = new RouteMatch(ignored -> recovered, context);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Callable<Feed> loader = () -> {
            calls.incrementAndGet();
            loading.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw failure;
        };
        Task first = start(() -> coordinator.fetch(match, context, loader));
        Task second = null;
        try {
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            second = start(() -> coordinator.fetch(match, context, loader));
            awaitBlocked(second.thread());
            assertEquals(1, calls.get());
            release.countDown();

            assertFailure(failure, first);
            assertFailure(failure, second);
            assertSame(recovered, coordinator.fetch(match, context, () -> {
                calls.incrementAndGet();
                return recovered;
            }));
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
            first.thread().join(5_000);
            if (second != null) second.thread().join(5_000);
        }
    }

    @Test
    void interruptingWaiterPreservesInterruptAndDoesNotCancelSharedLoad() throws Exception {
        FeedFetchCoordinator coordinator = new FeedFetchCoordinator();
        Feed feed = new Feed("News", "https://example.com", "", List.of());
        RouteContext context = context("https://example.com/feed.xml");
        RouteMatch match = new RouteMatch(ignored -> feed, context);
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Callable<Feed> loader = () -> {
            calls.incrementAndGet();
            loading.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return feed;
        };
        Task first = start(() -> coordinator.fetch(match, context, loader));
        Task waiter = null;
        Task laterWaiter = null;
        try {
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            waiter = start(() -> {
                try {
                    return coordinator.fetch(match, context, loader);
                } catch (InterruptedException e) {
                    interrupted.set(Thread.currentThread().isInterrupted());
                    throw e;
                }
            });
            awaitBlocked(waiter.thread());
            waiter.thread().interrupt();
            Task interruptedWaiter = waiter;
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> interruptedWaiter.result().get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue(interrupted.get());
            assertFalse(first.result().isDone());

            laterWaiter = start(() -> coordinator.fetch(match, context, loader));
            awaitBlocked(laterWaiter.thread());
            assertEquals(1, calls.get());
            release.countDown();
            assertSame(feed, first.result().get(5, TimeUnit.SECONDS));
            assertSame(feed, laterWaiter.result().get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            first.thread().join(5_000);
            if (waiter != null) waiter.thread().join(5_000);
            if (laterWaiter != null) laterWaiter.thread().join(5_000);
        }
    }

    private static Stream<RouteContext> differentContexts() {
        String feedUrl = "https://example.com/feed.xml";
        Duration ttl = Duration.ofMinutes(30);
        return Stream.of(
                new RouteContext("/other", Map.of(), Map.of(), ttl, feedUrl),
                new RouteContext("/news", Map.of("topic", "tech"), Map.of(), ttl, feedUrl),
                new RouteContext("/news", Map.of(), Map.of("language", "en"), ttl, feedUrl),
                new RouteContext("/news", Map.of(), Map.of(), Duration.ofMinutes(10), feedUrl),
                context("https://example.org/feed.xml"));
    }

    private static Stream<Exception> failures() {
        return Stream.of(new IOException("upstream connection closed"),
                new RouteException(RouteError.UPSTREAM_REQUEST_FAILED, "HTTP 503"),
                new CompletionException(new IOException("upstream task failed")),
                new CancellationException("upstream task cancelled"));
    }

    private static void assertFailure(Throwable expected, Task task) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> task.result().get(5, TimeUnit.SECONDS));
        assertSame(expected, failure.getCause());
    }

    private static RouteContext context(String feedUrl) {
        return new RouteContext("/news", Map.of(), Map.of(), Duration.ofMinutes(30), feedUrl);
    }

    private static Task start(Callable<Feed> task) {
        FutureTask<Feed> result = new FutureTask<>(task);
        Thread thread = Thread.ofVirtual().start(result);
        return new Task(thread, result);
    }

    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("caller did not block while the first load was running");
    }

    private record Task(Thread thread, FutureTask<Feed> result) {}
}
