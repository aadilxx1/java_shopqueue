package com.aadil.shopqueue.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A job as seen by clients. Deliberately a separate type from job/'s response so the
 * client never depends on queue internals.
 */
public record QueuedJob(
        UUID id,
        String type,
        JsonNode payload,
        Status status,
        String idempotencyKey,
        int attempts,
        int maxAttempts,
        Instant runAt,
        String lockedBy,
        Instant heartbeatAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }
}
