package com.aadil.shopqueue.client;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only way code outside job/ talks to the queue. Today it's backed by HTTP to
 * this same app; when job/ becomes its own service only the base URL changes.
 */
public interface JobQueueClient {

    /** Enqueues a job. With an idempotency key, repeating the call returns the original job. */
    QueuedJob enqueue(NewJob job);

    Optional<QueuedJob> get(UUID jobId);

    /** Claims up to {@code limit} due jobs of the given types for this worker. May return empty. */
    List<QueuedJob> claim(String workerId, Collection<String> types, int limit);

    /** @throws LockLostException if this worker no longer holds the job — stop working on it */
    void complete(UUID jobId, String workerId);

    /** @throws LockLostException if this worker no longer holds the job — stop working on it */
    void fail(UUID jobId, String workerId, String error);

    /** @throws LockLostException if this worker no longer holds the job — stop working on it */
    void heartbeat(UUID jobId, String workerId);
}
