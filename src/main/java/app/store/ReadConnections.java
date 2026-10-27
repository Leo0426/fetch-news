package app.store;

import java.io.Closeable;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import org.sqlite.SQLiteConfig;

/**
 * A few read-only connections to the store's database. In WAL mode they read the last committed state
 * without waiting for the store's lock, so page queries run in parallel with each other and with writes.
 */
final class ReadConnections implements Closeable {
    static final int SIZE = 4;

    @FunctionalInterface
    interface Query<T> {
        T run(Connection conn) throws SQLException;
    }

    private final String url;
    private final SQLiteConfig config = new SQLiteConfig();
    private final Semaphore permits = new Semaphore(SIZE, true);
    private final ConcurrentLinkedQueue<Connection> idle = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;

    ReadConnections(Path dbPath) {
        url = "jdbc:sqlite:" + dbPath;
        config.setReadOnly(true);
        config.setBusyTimeout(5000);
        config.setCacheSize(-8192);
    }

    <T> T read(Query<T> query) throws SQLException {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while waiting for a read connection", e);
        }
        Connection conn = null;
        try {
            if (closed) throw new SQLException("store is closed");
            conn = idle.poll();
            if (conn == null) conn = config.createConnection(url);
            return query.run(conn);
        } finally {
            if (conn != null) release(conn);
            permits.release();
        }
    }

    private void release(Connection conn) {
        try {
            if (closed || conn.isClosed()) {
                conn.close();
            } else {
                idle.offer(conn);
                // close() may have drained the queue between the check and the offer
                if (closed && idle.remove(conn)) conn.close();
            }
        } catch (SQLException ignored) {
            // a broken connection is dropped; the next read opens a fresh one
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Connection conn; (conn = idle.poll()) != null; ) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // closing anyway
            }
        }
    }
}
