package app.radar;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

public class RadarScheduler {
    private static final Logger log = LoggerFactory.getLogger(RadarScheduler.class);
    private final RadarConfig config;
    private final RadarService service;
    private final Clock clock;

    public RadarScheduler(RadarConfig config, RadarService service, Clock clock) {
        this.config = config;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        var settings = config;
        if (!service.enabled() || settings.scheduleCron().isBlank()) return;
        try {
            var now = clock.instant().atZone(ZoneId.of(settings.zoneId()));
            var cron = CronExpression.parse(settings.scheduleCron());
            // All slots on a local day produce the same previous-day report.
            for (int days = 0; days < 366; days++) {
                var day = now.toLocalDate().minusDays(days);
                var slot = cron.next(day.atStartOfDay(now.getZone()).minusNanos(1));
                if (slot != null && slot.toLocalDate().equals(day) && !slot.isAfter(now)) {
                    String target = day.minusDays(1).toString();
                    String key =
                            "scheduled:" + RadarJson.hash(List.of(target, settings.zoneId(), service.fingerprint()));
                    service.submit(target, settings.zoneId(), key);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("Radar schedule unavailable: {}", e.getClass().getSimpleName());
        }
    }
}
