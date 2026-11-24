package com.aadil.shopqueue.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

/**
 * A job to enqueue. Start with {@link #of} and add options with the {@code with*} methods:
 * <pre>{@code
 * NewJob.of("CHARGE_PAYMENT", payload).withIdempotencyKey("order-42-charge")
 * }</pre>
 * Null options fall back to the queue's defaults.
 */
public record NewJob(
        String type,
        JsonNode payload,
        String idempotencyKey,
        Integer maxAttempts,
        Long delaySeconds) {

    public static NewJob of(String type, JsonNode payload) {
        return new NewJob(type, payload, null, null, null);
    }

    public NewJob withIdempotencyKey(String key) {
        return new NewJob(type, payload, key, maxAttempts, delaySeconds);
    }

    public NewJob withMaxAttempts(int attempts) {
        return new NewJob(type, payload, idempotencyKey, attempts, delaySeconds);
    }

    public NewJob withDelay(Duration delay) {
        return new NewJob(type, payload, idempotencyKey, maxAttempts, delay.toSeconds());
    }
}
