package com.example.aggregation;

import java.util.concurrent.Callable;

public interface RetryTemplate {
    /**
     * Runs {@code work} with a per-attempt timeout and capped exponential backoff with full
     * jitter, never exceeding {@code deadline}. Never throws an Exception — every failure is a
     * {@link Failure}. Errors propagate by design.
     */
    <T> CallOutcome<T> execute(String op, Deadline deadline, Callable<T> work);
}