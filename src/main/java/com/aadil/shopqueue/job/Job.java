package com.aadil.shopqueue.job;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Read model of a row in {@code jobs}. Marked {@link Immutable} on purpose: every
 * state change goes through a single fenced SQL statement in {@link JobRepository},
 * never through Hibernate dirty checking.
 */
@Entity
@Immutable
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    private JobStatus status;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    private int attempts;

    @Column(name = "max_attempts")
    private int maxAttempts;

    @Column(name = "run_at")
    private Instant runAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Job() {
        // for Hibernate
    }

    public UUID getId() { return id; }
    public String getType() { return type; }
    public JsonNode getPayload() { return payload; }
    public JobStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getRunAt() { return runAt; }
    public String getLockedBy() { return lockedBy; }
    public Instant getLockedAt() { return lockedAt; }
    public Instant getHeartbeatAt() { return heartbeatAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
