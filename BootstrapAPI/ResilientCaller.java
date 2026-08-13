import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;

public final class ResilientCaller {

    /** Retry/timeout policy. Was a record; hand-written for Java 15. */
    public static final class Policy {
        private final int maxAttempts;
        private final Duration perAttemptTimeout;
        private final Duration totalBudget;
        private final Duration baseBackoff;
        private final Duration maxBackoff;

        public Policy(int maxAttempts, Duration perAttemptTimeout, Duration totalBudget,
                      Duration baseBackoff, Duration maxBackoff) {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
            this.maxAttempts = maxAttempts;
            this.perAttemptTimeout = Objects.requireNonNull(perAttemptTimeout);
            this.totalBudget = Objects.requireNonNull(totalBudget);
            this.baseBackoff = Objects.requireNonNull(baseBackoff);
            this.maxBackoff = Objects.requireNonNull(maxBackoff);
        }

        public static Policy defaults() {
            // 2s per attempt as specified; 5s hard ceiling so retries can't unbound the request.
            return new Policy(3, Duration.ofSeconds(2), Duration.ofSeconds(5),
                              Duration.ofMillis(50), Duration.ofMillis(400));
        }

        public int getMaxAttempts()             { return maxAttempts; }
        public Duration getPerAttemptTimeout()  { return perAttemptTimeout; }
        public Duration getTotalBudget()        { return totalBudget; }
        public Duration getBaseBackoff()        { return baseBackoff; }
        public Duration getMaxBackoff()         { return maxBackoff; }
    }

    private final ExecutorService io;
    private final Policy policy;

    public ResilientCaller(ExecutorService io, Policy policy) {
        this.io = Objects.requireNonNull(io);
        this.policy = Objects.requireNonNull(policy);
    }

    /**
     * Runs {@code call} with a per-attempt deadline, retrying 5xx / timeouts / thrown exceptions.
     * Returns empty on definitive failure — never throws, never returns a non-2xx response.
     */
    public <T extends HttpResponse> Optional<T> call(String op, Callable<T> call) {
        final long deadline = System.nanoTime() + policy.getTotalBudget().toNanos();

        for (int attempt = 1; attempt <= policy.getMaxAttempts(); attempt++) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) break;                        // budget exhausted
            long timeoutMs = Math.min(policy.getPerAttemptTimeout().toMillis(), remainingMs);

            Future<T> f;
            try {
                f = io.submit(call);
            } catch (RejectedExecutionException e) {
                // Bounded pool is saturated (or shut down). Shed load immediately: retrying
                // would only add pressure to a pool that is already out of threads.
                return Optional.empty();
            }

            try {
                T resp = f.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (resp != null && resp.isSuccess()) return Optional.of(resp);
                if (resp != null && !resp.isRetryable()) return Optional.empty(); // 4xx: retrying won't help
                // else: 5xx -> fall through to backoff
            } catch (TimeoutException e) {
                f.cancel(true);                                 // best-effort interrupt of the blocked call
            } catch (ExecutionException e) {
                // transport-level blowup (connection reset, parse error) — treat as retryable
            } catch (InterruptedException e) {
                f.cancel(true);
                Thread.currentThread().interrupt();             // restore the flag, abandon the call
                return Optional.empty();
            }

            if (attempt < policy.getMaxAttempts()) backoff(attempt, deadline);
        }
        return Optional.empty();
    }

    /** Exponential backoff with full jitter, clamped to the remaining budget. */
    private void backoff(int attempt, long deadline) {
        long exp     = policy.getBaseBackoff().toMillis() << (attempt - 1);
        long capped  = Math.min(exp, policy.getMaxBackoff().toMillis());
        long jitter  = ThreadLocalRandom.current().nextLong(capped + 1);   // full jitter avoids retry stampedes
        long left    = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        long sleepMs = Math.min(jitter, left);
        if (sleepMs <= 0) return;
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}