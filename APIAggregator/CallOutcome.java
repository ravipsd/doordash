package com.example.aggregation;

import java.util.Objects;
import java.util.Optional;

/** Result of a resilient call: the value on success, or *why* it failed. */
public record CallOutcome<T>(T value, Failure failure, int attempts, long elapsedMs) {

    public CallOutcome {
        Objects.requireNonNull(failure, "failure");
    }

    public static <T> CallOutcome<T> success(T value, int attempts, long elapsedMs) {
        return new CallOutcome<>(Objects.requireNonNull(value), Failure.NONE, attempts, elapsedMs);
    }

    public static <T> CallOutcome<T> failure(Failure failure, int attempts, long elapsedMs) {
        if (failure == Failure.NONE) throw new IllegalArgumentException("NONE is not a failure");
        return new CallOutcome<>(null, failure, attempts, elapsedMs);
    }

    public boolean isSuccess()      { return failure == Failure.NONE; }
    public Optional<T> toOptional() { return Optional.ofNullable(value); }

    /** Deliberately omits value — this lands in logs and payloads may hold PII. */
    @Override public String toString() {
        return "CallOutcome[" + failure + ", attempts=" + attempts + ", elapsedMs=" + elapsedMs + "]";
    }
}