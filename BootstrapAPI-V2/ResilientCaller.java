import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

public final class ResilientCaller {

    private static final Logger log = LoggerFactory.getLogger(ResilientCaller.class);

    private final ExecutorService io;
    private final Policy policy;

    public ResilientCaller(ExecutorService io, Policy policy) {
        this.io = Objects.requireNonNull(io, "io");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Runs {@code call} with a per-attempt deadline and bounded retries.
     * <p>Never throws a checked or runtime exception — every failure is reported as a
     * {@link CallOutcome.Failure}. {@link Error}s propagate by design: an OOM or
     * NoClassDefFoundError means the JVM is compromised, and degrading gracefully past it
     * hides a fatal condition that the next request will hit anyway.
     */
    public <T extends HttpResponse> CallOutcome<T> call(String op, Callable<T> call) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(call, "call");

        final long start = System.nanoTime();
        final long deadline = start + policy.getTotalBudget().toNanos();
        final int maxAttempts = policy.getMaxAttempts();
        // MDC is thread-local: without capturing it here, log lines emitted on io threads
        // lose the correlation id and become unjoinable with the request that caused them.
        final Map<String, String> callerCtx = MDC.getCopyOfContextMap();

        Throwable lastCause = null;
        int lastStatus = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) {
                log.warn("op={} outcome=budget_exceeded attempts={} elapsedMs={} budgetMs={}",
                        op, attempt - 1, elapsedMs(start), policy.getTotalBudget().toMillis());
                return CallOutcome.failure(CallOutcome.Failure.BUDGET_EXCEEDED, attempt - 1, elapsedMs(start));
            }
            long timeoutMs = Math.min(policy.getPerAttemptTimeout().toMillis(), remainingMs);

            Future<T> f;
            try {
                f = io.submit(withMdc(callerCtx, call));
            } catch (RejectedExecutionException e) {
                // Pool saturated or shut down. Shed immediately — retrying adds pressure to a
                // pool that is already out of threads. No stack trace: it tells you nothing
                // beyond this line, and rejection storms would flood the log.
                log.error("op={} outcome=rejected attempt={} elapsedMs={} reason=io_pool_saturated_or_shutdown",
                        op, attempt, elapsedMs(start));
                return CallOutcome.failure(CallOutcome.Failure.REJECTED, attempt - 1, elapsedMs(start));
            }

            try {
                T resp = f.get(timeoutMs, TimeUnit.MILLISECONDS);

                if (resp == null) {
                    log.error("op={} outcome=null_response attempt={} elapsedMs={} — service contract violated",
                            op, attempt, elapsedMs(start));
                    return CallOutcome.failure(CallOutcome.Failure.NON_RETRYABLE_ERROR, attempt, elapsedMs(start));
                }

                lastStatus = resp.getStatusCode();

                if (resp.isSuccess()) {
                    if (attempt > 1) {
                        // Recovered-after-retry is worth INFO: it is the signal that a
                        // downstream is degrading before it fully fails.
                        log.info("op={} outcome=success attempts={} elapsedMs={} status={} recovered=true",
                                op, attempt, elapsedMs(start), lastStatus);
                    } else if (log.isDebugEnabled()) {
                        log.debug("op={} outcome=success attempts=1 elapsedMs={} status={}",
                                op, elapsedMs(start), lastStatus);
                    }
                    return CallOutcome.success(resp, attempt, elapsedMs(start));
                }

                if (!resp.isRetryable()) {
                    log.error("op={} outcome=non_retryable_status status={} attempt={} elapsedMs={}",
                            op, lastStatus, attempt, elapsedMs(start));
                    return CallOutcome.failure(CallOutcome.Failure.NON_RETRYABLE_STATUS, attempt, elapsedMs(start));
                }

                log.warn("op={} event=attempt_failed attempt={}/{} status={} action=retry",
                        op, attempt, maxAttempts, lastStatus);

            } catch (TimeoutException e) {
                f.cancel(true);   // reclaim the pool thread; best-effort if it is blocked in a socket read
                lastCause = e;
                log.warn("op={} event=attempt_timeout attempt={}/{} timeoutMs={} action=retry",
                        op, attempt, maxAttempts, timeoutMs);

            } catch (ExecutionException e) {
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;

                if (cause instanceof Error) {
                    // Do NOT swallow. OOM / NoClassDefFoundError / StackOverflow mean the JVM is
                    // in trouble; a graceful default here hides it and the next request re-hits it.
                    log.error("op={} outcome=fatal attempt={} — propagating Error", op, attempt, cause);
                    throw (Error) cause;
                }
                lastCause = cause;

                if (!policy.getRetryableCause().test(cause)) {
                    log.error("op={} outcome=non_retryable_exception attempt={} elapsedMs={}",
                            op, attempt, elapsedMs(start), cause);
                    return CallOutcome.failure(CallOutcome.Failure.NON_RETRYABLE_ERROR, attempt, elapsedMs(start));
                }
                // Class + message only at WARN. Full stack is logged once at the terminal
                // ERROR below — three stack traces per failed call is how logs become unreadable.
                log.warn("op={} event=attempt_failed attempt={}/{} cause={}: {} action=retry",
                        op, attempt, maxAttempts, cause.getClass().getSimpleName(), cause.getMessage());

            } catch (InterruptedException e) {
                f.cancel(true);
                Thread.currentThread().interrupt();   // restore the flag we just cleared
                log.warn("op={} outcome=interrupted attempt={} elapsedMs={}", op, attempt, elapsedMs(start));
                return CallOutcome.failure(CallOutcome.Failure.INTERRUPTED, attempt, elapsedMs(start));

            } catch (CancellationException e) {
                // Future cancelled by someone else (pool shutdownNow). Same class of event as interrupt.
                log.warn("op={} outcome=cancelled attempt={} elapsedMs={}", op, attempt, elapsedMs(start));
                return CallOutcome.failure(CallOutcome.Failure.INTERRUPTED, attempt, elapsedMs(start));
            }

            if (attempt < maxAttempts) backoff(op, attempt, deadline);
        }

        // Terminal failure: this is the one place the full stack trace is worth printing.
        log.error("op={} outcome=retries_exhausted attempts={} elapsedMs={} lastStatus={}",
                op, maxAttempts, elapsedMs(start), lastStatus, lastCause);
        return CallOutcome.failure(CallOutcome.Failure.RETRIES_EXHAUSTED, maxAttempts, elapsedMs(start));
    }

    /** Re-establishes the caller's logging context on the pool thread, then restores it. */
    private static <T> Callable<T> withMdc(Map<String, String> ctx, Callable<T> delegate) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (ctx != null) MDC.setContextMap(ctx); else MDC.clear();
            try {
                return delegate.call();
            } finally {
                // Pool threads are reused — leaking context would mislabel the next request.
                if (previous != null) MDC.setContextMap(previous); else MDC.clear();
            }
        };
    }

    private void backoff(String op, int attempt, long deadline) {
        long exp     = policy.getBaseBackoff().toMillis() << (attempt - 1);
        long capped  = Math.min(exp, policy.getMaxBackoff().toMillis());
        long jitter  = ThreadLocalRandom.current().nextLong(capped + 1);
        long left    = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        long sleepMs = Math.min(jitter, left);
        if (sleepMs <= 0) return;

        if (log.isDebugEnabled()) log.debug("op={} event=backoff sleepMs={} beforeAttempt={}", op, sleepMs, attempt + 1);
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // loop's next get() sees it and exits cleanly
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}