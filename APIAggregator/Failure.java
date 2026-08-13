package com.example.aggregation;

public enum Failure {
    NONE,                  // success
    NON_RETRYABLE_STATUS,  // upstream 4xx — our request is wrong
    NON_RETRYABLE_ERROR,   // exception classified as a bug, or a null payload
    RETRIES_EXHAUSTED,     // all attempts used, all retryable failures
    DEADLINE_EXCEEDED,     // request-wide budget gone
    ABORTED,               // a sibling failed under FAIL_FAST
    REJECTED,              // thread pool saturated — load shed
    INTERRUPTED            // shutdown in progress
}