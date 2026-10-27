package app.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VacuumTest {
    @TempDir
    Path tempDir;

    @Test
    void vacuumsOnlyWhenFreePagesPassBothThresholds() throws Exception {
        Path database = tempDir.resolve("feed.db");
        try (FeedStore store = new FeedStore(database, new ObjectMapper())) {
            assertFalse(store.vacuumIfFragmented(1));
            synchronized (store) {
                try (Statement s = store.conn.createStatement()) {
                    s.execute("CREATE TABLE scratch(body BLOB)");
                    s.execute("""
                            WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 400)
                            INSERT INTO scratch SELECT randomblob(8192) FROM n""");
                    s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    s.execute("DROP TABLE scratch");
                    s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }
            }
            long fragmented = Files.size(database);

            assertFalse(store.vacuumIfFragmented(64L << 20), "below the byte threshold");
            assertTrue(store.vacuumIfFragmented(1 << 20));

            assertTrue(Files.size(database) < fragmented / 4);
            assertTrue(store.searchIndex().search("anything", 10).isEmpty(), "the store keeps working afterwards");
        }
    }
}
