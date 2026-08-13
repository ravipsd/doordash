package com.example.aggregation;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Request-wide budget shared by every branch and every retry attempt. */
public final class Deadline {

    private final long deadlineNanos;
    private volatile boolean aborted;   // written by the first failing branch, read by siblings

    private Deadline(long deadlineNanos) { this.deadlineNanos = deadlineNanos; }

    public static Deadline after(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos());
    }

    /** Clamped at 0 so it is always safe to pass to Future.get(timeout, unit). */
    public long remainingMillis() {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    public boolean expired() { return remainingMillis() <= 0; }

    /** Cooperative cancellation. CompletableFuture.cancel does not interrupt supplyAsync tasks. */
    public void abort()      { aborted = true; }
    public boolean isAborted() { return aborted; }
}