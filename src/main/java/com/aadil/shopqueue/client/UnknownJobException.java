package com.aadil.shopqueue.client;

/** The job id doesn't exist. */
public class UnknownJobException extends JobQueueClientException {

    public UnknownJobException(String message) {
        super(404, message);
    }
}
