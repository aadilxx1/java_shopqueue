package com.aadil.shopqueue.job;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String type,
        JsonNode payload,
        JobStatus status,
        String idempotencyKey,
        int attempts,
        int maxAttempts,
        Instant runAt,
        String lockedBy,
        Instant lockedAt,
        Instant heartbeatAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getType(),
                job.getPayload(),
                job.getStatus(),
                job.getIdempotencyKey(),
                job.getAttempts(),
                job.getMaxAttempts(),
                job.getRunAt(),
                job.getLockedBy(),
                job.getLockedAt(),
                job.getHeartbeatAt(),
                job.getLastError(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
