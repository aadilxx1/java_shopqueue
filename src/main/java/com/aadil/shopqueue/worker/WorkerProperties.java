package com.aadil.shopqueue.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled           off by default so tests and API-only deployments don't consume jobs
 * @param id                unique per worker process; generated when blank
 * @param concurrency       max jobs this worker runs at once
 * @param pollInterval      how long to wait before polling again when there's no work
 * @param heartbeatInterval how often a running job's heartbeat is sent; keep well under the reaper's
 *                          timeout ({@code shopqueue.reaper.timeout}, default 60s) or live jobs get reclaimed
 * @param shutdownTimeout   how long shutdown waits for running jobs before interrupting them
 */
@ConfigurationProperties("shopqueue.worker")
public record WorkerProperties(
        @DefaultValue("false") boolean enabled,
        String id,
        @DefaultValue("4") int concurrency,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("10s") Duration heartbeatInterval,
        @DefaultValue("30s") Duration shutdownTimeout) {
}
