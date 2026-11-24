package com.aadil.shopqueue.job;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically reclaims jobs whose worker crashed or hung, so they don't stay
 * RUNNING forever. Turn off with {@code shopqueue.reaper.enabled=false}.
 */
@Component
@EnableScheduling
@EnableConfigurationProperties(ReaperProperties.class)
@ConditionalOnProperty(prefix = "shopqueue.reaper", name = "enabled", havingValue = "true", matchIfMissing = true)
class JobReaper {

    private static final Logger log = LoggerFactory.getLogger(JobReaper.class);

    private final JobService service;
    private final ReaperProperties properties;

    JobReaper(JobService service, ReaperProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${shopqueue.reaper.interval:15s}",
            initialDelayString = "${shopqueue.reaper.interval:15s}")
    void reap() {
        try {
            List<UUID> reclaimed = service.reapStale(properties.timeout());
            if (!reclaimed.isEmpty()) {
                log.warn("Reclaimed {} job(s) with no heartbeat for {}: {}",
                        reclaimed.size(), properties.timeout(), reclaimed);
            }
        } catch (RuntimeException e) {
            // Never let one bad run (e.g. database blip) kill the schedule.
            log.error("Reaper run failed; will retry in {}", properties.interval(), e);
        }
    }
}
