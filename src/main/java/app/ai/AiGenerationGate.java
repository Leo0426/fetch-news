package app.ai;

import java.util.concurrent.Semaphore;

public final class AiGenerationGate {
    private final Semaphore semaphore = new Semaphore(1, true);

    public Permit acquire() throws InterruptedException {
        semaphore.acquire();
        return new Permit();
    }

    public final class Permit implements AutoCloseable {
        private boolean released;
        private Permit() {}
        @Override public void close() {
            if (!released) { released = true; semaphore.release(); }
        }
    }
}
