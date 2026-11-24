package com.aadil.shopqueue.client;

/**
 * This worker no longer holds the job: it was already finished, or reclaimed after a
 * stale heartbeat, or never claimed by this worker. Stop working on it and move on.
 */
public class LockLostException extends JobQueueClientException {

    public LockLostException(String message) {
        super(409, message);
    }
}
