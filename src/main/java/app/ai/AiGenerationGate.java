package app.ai;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Admits one model generation at a time. Waiting interactive generations go before any waiting
 * background one; within a priority, first come first served. A running generation is never preempted.
 */
public final class AiGenerationGate {
    public enum Priority { INTERACTIVE, BACKGROUND }

    private record Waiter(Priority priority, long ticket) {}

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final PriorityQueue<Waiter> waiting = new PriorityQueue<>(
            Comparator.comparing(Waiter::priority).thenComparingLong(Waiter::ticket));
    private long nextTicket;
    private boolean busy;

    public Permit acquire(Priority priority) throws InterruptedException {
        lock.lock();
        try {
            Waiter self = new Waiter(priority, nextTicket++);
            waiting.add(self);
            try {
                while (busy || waiting.peek() != self) changed.await();
            } catch (InterruptedException e) {
                waiting.remove(self);
                changed.signalAll();
                throw e;
            }
            waiting.poll();
            busy = true;
            return new Permit();
        } finally {
            lock.unlock();
        }
    }

    public final class Permit implements AutoCloseable {
        private boolean released;

        private Permit() {}

        @Override
        public void close() {
            lock.lock();
            try {
                if (released) return;
                released = true;
                busy = false;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
}
