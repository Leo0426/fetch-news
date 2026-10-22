package app.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.feishu.FeishuConfig;
import app.feishu.FeishuConfigStore;
import app.feishu.RadarDeliveryService;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadarDeliveryTest {
    @TempDir Path directory;
    @Test void retriesOnlyUnsentPagesAndRequiresConfirmationForUnknownDelivery() throws Exception {
        try(var env=new RadarTestEnvironment(directory,"{\"enabled\":true,\"profileSettings\":{\"tech-news\":{\"threshold\":7,\"topicDedup\":false}}}")) {
            env.save("https://a.test/1");env.save("https://a.test/2");
            var run=env.service.submit(LocalDate.now(ZoneOffset.UTC).toString(),"UTC","report");
            assertEquals(2,run.totalCandidates());
            env.worker.processNext();
            var config=new FeishuConfigStore(directory.resolve("feishu.json"),env.mapper);
            config.save(new FeishuConfig("https://example.test/hook/secret",true,1,Map.of()));
            var calls=new AtomicInteger();
            try(var delivery=new RadarDeliveryService(env.store,config,env.mapper,(url,body)->calls.incrementAndGet()==2 ? false : true)) {
                var job=delivery.submit(run.runId());delivery.processNext();
                assertEquals("failed",env.store.loadRadarJob(job.id()).state());
                delivery.retry(job.id(),false);delivery.processNext();
                assertEquals("success",env.store.loadRadarJob(job.id()).state());
                assertEquals(3,calls.get());
                assertEquals(job.id(),delivery.submit(run.runId()).id());delivery.processNext();assertEquals(3,calls.get());
            }
            config.save(new FeishuConfig("https://example.test/hook/second",true,1,Map.of()));
            try(var delivery=new RadarDeliveryService(env.store,config,env.mapper,(url,body)->{throw new java.io.IOException("timeout");})) {
                var job=delivery.submit(run.runId());delivery.processNext();
                assertEquals("paused",env.store.loadRadarJob(job.id()).state());
                assertThrows(IllegalStateException.class,()->delivery.retry(job.id(),false));
                delivery.retry(job.id(),true);assertEquals("queued",env.store.loadRadarJob(job.id()).state());
            }
        }
    }
}
