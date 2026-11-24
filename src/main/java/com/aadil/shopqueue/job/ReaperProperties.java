package com.aadil.shopqueue.job;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled  on by default: the reaper runs wherever the queue runs
 * @param timeout  a RUNNING job with no heartbeat for this long is reclaimed; keep it
 *                 several times the worker's heartbeat interval (default 10s)
 * @param interval how often to look for stale jobs
 */
@ConfigurationProperties("shopqueue.reaper")
public record ReaperProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60s") Duration timeout,
        @DefaultValue("15s") Duration interval) {
}
