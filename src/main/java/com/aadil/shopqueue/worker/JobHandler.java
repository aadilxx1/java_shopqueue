package com.aadil.shopqueue.worker;

import com.aadil.shopqueue.client.QueuedJob;

/**
 * Processes one job type. Register an implementation as a Spring bean and the
 * worker will start claiming jobs of that type.
 *
 * <p>Delivery is at-least-once: a job can run again if a worker crashes, loses its
 * lock, or can't report the result. Handlers must be idempotent (e.g. pass the
 * order id as the payment gateway's idempotency key).
 *
 * <p>If the worker loses the lock mid-run, it interrupts the handler thread. Long
 * handlers should use interruptible calls or check {@link Thread#isInterrupted()}.
 */
public interface JobHandler {

    /** The job type this handler processes, e.g. {@code CHARGE_PAYMENT}. */
    String type();

    /** Return normally to complete the job; throw to fail it (it's retried while attempts remain). */
    void handle(QueuedJob job) throws Exception;
}
