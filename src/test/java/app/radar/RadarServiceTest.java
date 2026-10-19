package app.radar;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadarServiceTest {
    @TempDir Path directory;
    @Test void duplicateSubmissionsShareRunAndExplicitRerunCreatesVersion() throws Exception {
        try(var env=new RadarTestEnvironment(directory,"{\"enabled\":true}")) {
            env.save("https://a.test/1");
            String date=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var first=pool.submit(()->env.service.submit(date,"Asia/Shanghai","same"));
                var second=pool.submit(()->env.service.submit(date,"Asia/Shanghai","same"));
                assertEquals(first.get().runId(),second.get().runId());
            }
            assertEquals(2,env.service.submit(date,"Asia/Shanghai","new").version());
            env.config.save(RadarConfig.defaults());
            assertThrows(IllegalStateException.class,()->env.service.submit(date,"Asia/Shanghai","disabled"));
        }
    }
}
