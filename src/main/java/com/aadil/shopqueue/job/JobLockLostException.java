package com.aadil.shopqueue.job;

import java.util.UUID;

/**
 * The worker tried to act on a job it no longer holds (never claimed it, already
 * finished it, or the job was reclaimed after a stale heartbeat). The worker should
 * stop working on it.
 */
public class JobLockLostException extends RuntimeException {

    public JobLockLostException(UUID id, String workerId) {
        super("Job " + id + " is not running under worker " + workerId);
    }
}
