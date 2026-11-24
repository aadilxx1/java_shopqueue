package com.aadil.shopqueue.client;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param baseUrl        where the queue's REST API lives (this app while we're a monolith)
 * @param connectTimeout how long to wait for a TCP connection
 * @param readTimeout    how long to wait for a response
 */
@ConfigurationProperties("shopqueue.queue-client")
public record JobQueueClientProperties(
        @DefaultValue("http://localhost:8080") URI baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout) {
}
