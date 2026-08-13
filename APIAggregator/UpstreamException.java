package com.example.aggregation;

/** Thrown by clients. Carries the HTTP status so the retry policy can classify it. */
public class UpstreamException extends RuntimeException {

    private final int statusCode;   // 0 == transport-level (connect reset, DNS, parse)

    public UpstreamException(String message, int statusCode) { super(message); this.statusCode = statusCode; }
    public UpstreamException(String message, Throwable cause) { super(message, cause); this.statusCode = 0; }

    public int getStatusCode() { return statusCode; }

    /** Transport failures, 5xx and 429 are worth another attempt; 4xx never is. */
    public boolean isRetryable() { return statusCode == 0 || statusCode >= 500 || statusCode == 429; }
}