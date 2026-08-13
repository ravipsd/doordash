package com.example.aggregation;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

public record RetryPolicy(int maxAttempts,
                          Duration perAttemptTimeout,
                          Duration baseBackoff,
                          Duration maxBackoff,
                          Predicate<Throwable> retryableCause) {

    /** Errors and interrupts are handled before this runs. */
    public static final Predicate<Throwable> DEFAULT_RETRYABLE_CAUSE = t -> {
        if (t instanceof UpstreamException ue) return ue.isRetryable();
        return !(t instanceof InterruptedException)
            && !(t instanceof NullPointerException)
            && !(t instanceof IllegalArgumentException)
            && !(t instanceof IllegalStateException)
            && !(t instanceof ClassCastException)
            && !(t instanceof UnsupportedOperationException);
    };

    public RetryPolicy {
        if (maxAttempts < 1)  throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (maxAttempts > 30) throw new IllegalArgumentException("maxAttempts > 30 overflows the backoff shift");
        requirePositive(perAttemptTimeout, "perAttemptTimeout");
        Objects.requireNonNull(baseBackoff, "baseBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        Objects.requireNonNull(retryableCause, "retryableCause");
    }

    private static void requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative() || d.isZero()) throw new IllegalArgumentException(name + " must be > 0");
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(300), Duration.ofMillis(40),
                               Duration.ofMillis(200), DEFAULT_RETRYABLE_CAUSE);
    }
}