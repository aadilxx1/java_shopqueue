package com.aadil.shopqueue.job;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * All writes are single native statements so each transition is atomic in the
 * database. Complete/fail/heartbeat only match rows where {@code locked_by} is the
 * caller: a worker that lost its lock (reaped, reclaimed) updates 0 rows.
 */
public interface JobRepository extends JpaRepository<Job, UUID> {

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    /** Returns the new id, or empty if a job with this idempotency key already exists. */
    @Query(value = """
            INSERT INTO jobs (type, payload, idempotency_key, max_attempts, run_at)
            VALUES (:type, CAST(:payload AS jsonb), CAST(:idempotencyKey AS text), :maxAttempts,
                    now() + make_interval(secs => :delaySeconds))
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING id
            """, nativeQuery = true)
    Optional<UUID> insertUnlessDuplicate(@Param("type") String type,
                                         @Param("payload") String payload,
                                         @Param("idempotencyKey") String idempotencyKey,
                                         @Param("maxAttempts") int maxAttempts,
                                         @Param("delaySeconds") long delaySeconds);

    /**
     * Atomically picks up to {@code limit} due jobs and marks them RUNNING for this
     * worker. SKIP LOCKED makes concurrent claimers skip rows another transaction is
     * already claiming instead of blocking on them or claiming them twice.
     */
    @Query(value = """
            UPDATE jobs
            SET status = 'RUNNING',
                locked_by = :workerId,
                locked_at = now(),
                heartbeat_at = now(),
                attempts = attempts + 1
            WHERE id IN (
                SELECT id FROM jobs
                WHERE status = 'PENDING'
                  AND run_at <= now()
                  AND type IN (:types)
                ORDER BY run_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<Job> claim(@Param("workerId") String workerId,
                    @Param("types") Collection<String> types,
                    @Param("limit") int limit);

    @Modifying
    @Query(value = """
            UPDATE jobs
            SET status = 'SUCCEEDED',
                locked_by = NULL, locked_at = NULL, heartbeat_at = NULL
            WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId
            """, nativeQuery = true)
    int complete(@Param("id") UUID id, @Param("workerId") String workerId);

    /**
     * Goes back to PENDING while attempts remain, otherwise FAILED for good.
     * Retries are immediate for now; backoff comes with the reliability work.
     */
    @Modifying
    @Query(value = """
            UPDATE jobs
            SET status = CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'PENDING' END,
                run_at = CASE WHEN attempts >= max_attempts THEN run_at ELSE now() END,
                last_error = left(:error, 4000),
                locked_by = NULL, locked_at = NULL, heartbeat_at = NULL
            WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId
            """, nativeQuery = true)
    int fail(@Param("id") UUID id, @Param("workerId") String workerId, @Param("error") String error);

    @Modifying
    @Query(value = """
            UPDATE jobs
            SET heartbeat_at = now()
            WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId
            """, nativeQuery = true)
    int heartbeat(@Param("id") UUID id, @Param("workerId") String workerId);

    /**
     * Takes back RUNNING jobs whose worker stopped heartbeating: PENDING again while
     * attempts remain, otherwise FAILED. Clearing locked_by is what fences the old
     * worker out — its next complete/fail/heartbeat matches 0 rows.
     *
     * <p>Safe to run from several instances at once: if a heartbeat or another reaper
     * updates the row first, Postgres re-checks the WHERE clause and skips it.
     * SET expressions see the row's old values, so last_error can name the old worker.
     */
    @Query(value = """
            UPDATE jobs
            SET status = CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'PENDING' END,
                run_at = CASE WHEN attempts >= max_attempts THEN run_at ELSE now() END,
                last_error = 'Reclaimed: worker ' || locked_by
                             || ' stopped heartbeating (last heartbeat ' || heartbeat_at::text || ')',
                locked_by = NULL, locked_at = NULL, heartbeat_at = NULL
            WHERE status = 'RUNNING'
              AND heartbeat_at < now() - make_interval(secs => :timeoutSeconds)
            RETURNING id
            """, nativeQuery = true)
    List<UUID> reapStale(@Param("timeoutSeconds") double timeoutSeconds);
}
