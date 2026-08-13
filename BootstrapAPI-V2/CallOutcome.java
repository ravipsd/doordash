import java.util.Objects;
import java.util.Optional;

/** Result of a resilient call: the value on success, or *why* it failed. */
public final class CallOutcome<T> {

    public enum Failure {
        NONE,                  // success
        NON_RETRYABLE_STATUS,  // 4xx — our request is wrong, retrying won't help
        NON_RETRYABLE_ERROR,   // exception classified as a bug, or a null response
        RETRIES_EXHAUSTED,     // ran out of attempts, all 5xx/timeout
        BUDGET_EXCEEDED,       // ran out of wall clock
        REJECTED,              // thread pool saturated — load shed
        INTERRUPTED            // shutdown in progress; NOT a degradable failure
    }

    private final T value;              // null unless success
    private final Failure failure;
    private final int attempts;
    private final long elapsedMs;

    private CallOutcome(T value, Failure failure, int attempts, long elapsedMs) {
        this.value = value;
        this.failure = Objects.requireNonNull(failure);
        this.attempts = attempts;
        this.elapsedMs = elapsedMs;
    }

    static <T> CallOutcome<T> success(T value, int attempts, long elapsedMs) {
        return new CallOutcome<>(Objects.requireNonNull(value), Failure.NONE, attempts, elapsedMs);
    }

    static <T> CallOutcome<T> failure(Failure failure, int attempts, long elapsedMs) {
        if (failure == Failure.NONE) throw new IllegalArgumentException("NONE is not a failure");
        return new CallOutcome<>(null, failure, attempts, elapsedMs);
    }

    public boolean isSuccess()      { return failure == Failure.NONE; }
    public T getValue()             { return value; }          // null unless isSuccess()
    public Failure getFailure()     { return failure; }
    public int getAttempts()        { return attempts; }
    public long getElapsedMs()      { return elapsedMs; }
    public Optional<T> toOptional() { return Optional.ofNullable(value); }

    @Override public String toString() {
        return "CallOutcome[" + failure + ", attempts=" + attempts + ", elapsedMs=" + elapsedMs + "]";
    }
}