import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

public static final class Policy {

    /**
     * Which *causes* are worth retrying. Errors and interrupts are handled before this runs.
     * Default: retry everything except exceptions that indicate a bug in our own code —
     * retrying a deterministic NPE three times just triples the latency of a guaranteed failure.
     * Deployment-specific: an HTTP client wrapping 5xx in a custom RuntimeException needs its
     * own predicate, which is why this is configurable rather than hard-coded.
     */
    public static final Predicate<Throwable> DEFAULT_RETRYABLE_CAUSE = t ->
               !(t instanceof InterruptedException)
            && !(t instanceof NullPointerException)
            && !(t instanceof IllegalArgumentException)
            && !(t instanceof IllegalStateException)
            && !(t instanceof ClassCastException)
            && !(t instanceof UnsupportedOperationException);

    private final int maxAttempts;
    private final Duration perAttemptTimeout;
    private final Duration totalBudget;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Predicate<Throwable> retryableCause;

    public Policy(int maxAttempts, Duration perAttemptTimeout, Duration totalBudget,
                  Duration baseBackoff, Duration maxBackoff, Predicate<Throwable> retryableCause) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (maxAttempts > 30) throw new IllegalArgumentException("maxAttempts > 30 overflows the backoff shift");
        this.maxAttempts = maxAttempts;
        this.perAttemptTimeout = requirePositive(perAttemptTimeout, "perAttemptTimeout");
        this.totalBudget = requirePositive(totalBudget, "totalBudget");
        this.baseBackoff = Objects.requireNonNull(baseBackoff, "baseBackoff");
        this.maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff");
        this.retryableCause = Objects.requireNonNull(retryableCause, "retryableCause");
    }

    private static Duration requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative() || d.isZero()) throw new IllegalArgumentException(name + " must be > 0");
        return d;
    }

    public static Policy defaults() {
        return new Policy(3, Duration.ofSeconds(2), Duration.ofSeconds(5),
                          Duration.ofMillis(50), Duration.ofMillis(400), DEFAULT_RETRYABLE_CAUSE);
    }

    public int getMaxAttempts()                    { return maxAttempts; }
    public Duration getPerAttemptTimeout()         { return perAttemptTimeout; }
    public Duration getTotalBudget()               { return totalBudget; }
    public Duration getBaseBackoff()               { return baseBackoff; }
    public Duration getMaxBackoff()                { return maxBackoff; }
    public Predicate<Throwable> getRetryableCause(){ return retryableCause; }
}