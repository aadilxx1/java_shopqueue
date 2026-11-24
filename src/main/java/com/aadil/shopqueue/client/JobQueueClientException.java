package com.aadil.shopqueue.client;

/** The queue rejected a request (bad input, server error, ...). */
public class JobQueueClientException extends RuntimeException {

    private final int statusCode;

    public JobQueueClientException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
