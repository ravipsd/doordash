package com.example.aggregation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

public final class DefaultRetryTemplate implements RetryTemplate {

    private static final Logger log = LoggerFactory.getLogger(DefaultRetryTemplate.class);

    private final ExecutorService io;
    private final RetryPolicy policy;
    private final MeterRegistry registry;

    public DefaultRetryTemplate(ExecutorService io, RetryPolicy policy, MeterRegistry registry) {
        this.io = Objects.requireNonNull(io, "io");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public <T> CallOutcome<T> execute(String op, Deadline deadline, Callable<T> work) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(work, "work");

        final long start = System.nanoTime();
        final int maxAttempts = policy.maxAttempts();
        // MDC is thread-local: captured here so log lines on io threads keep the correlation id.
        final Map<String, String> callerCtx = MDC.getCopyOfContextMap();
        Throwable lastCause = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {

            if (deadline.isAborted()) {
                registry.counter("upstream.aborted", "upstream", op).increment();
                log.debug("op={} outcome=aborted attempt={}", op, attempt);
                return CallOutcome.failure(Failure.ABORTED, attempt - 1, elapsedMs(start));
            }

            long remaining = deadline.remainingMillis();
            if (remaining <= 0) {
                registry.counter("upstream.deadline_exceeded", "upstream", op).increment();
                log.warn("op={} outcome=deadline_exceeded attempts={} elapsedMs={}",
                        op, attempt - 1, elapsedMs(start));
                return CallOutcome.failure(Failure.DEADLINE_EXCEEDED, attempt - 1, elapsedMs(start));
            }
            // Bounded by BOTH the per-call config and the request budget — whichever is tighter.
            long attemptMs = Math.min(policy.perAttemptTimeout().toMillis(), remaining);

            Future<T> f;
            try {
                f = io.submit(withMdc(callerCtx, work));
            } catch (RejectedExecutionException e) {
                registry.counter("upstream.rejected", "upstream", op).increment();
                log.error("op={} outcome=rejected attempt={} reason=io_pool_saturated_or_shutdown", op, attempt);
                return CallOutcome.failure(Failure.REJECTED, attempt - 1, elapsedMs(start));
            }

            Timer.Sample sample = Timer.start(registry);
            try {
                T value = f.get(attemptMs, TimeUnit.MILLISECONDS);
                sample.stop(timer(op, "success"));

                if (value == null) {
                    log.error("op={} outcome=null_response attempt={} — client contract violated", op, attempt);
                    return CallOutcome.failure(Failure.NON_RETRYABLE_ERROR, attempt, elapsedMs(start));
                }
                if (attempt > 1) {
                    log.info("op={} outcome=success attempts={} elapsedMs={} recovered=true",
                            op, attempt, elapsedMs(start));
                } else if (log.isDebugEnabled()) {
                    log.debug("op={} outcome=success attempts=1 elapsedMs={}", op, elapsedMs(start));
                }
                return CallOutcome.success(value, attempt, elapsedMs(start));

            } catch (TimeoutException e) {
                f.cancel(true);   // reclaim the io thread; best-effort if blocked in a socket read
                sample.stop(timer(op, "timeout"));
                registry.counter("upstream.timeouts", "upstream", op).increment();
                lastCause = e;
                log.warn("op={} event=attempt_timeout attempt={}/{} timeoutMs={} action=retry",
                        op, attempt, maxAttempts, attemptMs);

            } catch (ExecutionException e) {
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                sample.stop(timer(op, "error"));
                if (cause instanceof Error err) {
                    // Never swallow: OOM / NoClassDefFoundError mean the JVM is compromised.
                    log.error("op={} outcome=fatal attempt={} — propagating Error", op, attempt, err);
                    throw err;
                }
                lastCause = cause;
                if (!policy.retryableCause().test(cause)) {
                    Failure kind = (cause instanceof UpstreamException ue && ue.getStatusCode() > 0)
                            ? Failure.NON_RETRYABLE_STATUS : Failure.NON_RETRYABLE_ERROR;
                    log.error("op={} outcome={} attempt={} elapsedMs={}", op, kind, attempt, elapsedMs(start), cause);
                    return CallOutcome.failure(kind, attempt, elapsedMs(start));
                }
                // Class + message only here; the full stack is logged once at the terminal ERROR.
                log.warn("op={} event=attempt_failed attempt={}/{} cause={}: {} action=retry",
                        op, attempt, maxAttempts, cause.getClass().getSimpleName(), cause.getMessage());

            } catch (InterruptedException e) {
                f.cancel(true);
                sample.stop(timer(op, "interrupted"));
                Thread.currentThread().interrupt();   // restore the flag we just cleared
                log.warn("op={} outcome=interrupted attempt={} elapsedMs={}", op, attempt, elapsedMs(start));
                return CallOutcome.failure(Failure.INTERRUPTED, attempt, elapsedMs(start));

            } catch (CancellationException e) {
                sample.stop(timer(op, "cancelled"));
                log.warn("op={} outcome=cancelled attempt={} elapsedMs={}", op, attempt, elapsedMs(start));
                return CallOutcome.failure(Failure.INTERRUPTED, attempt, elapsedMs(start));
            }

            if (attempt < maxAttempts) {
                registry.counter("upstream.retries", "upstream", op).increment();
                backoff(op, attempt, deadline);
            }
        }

        log.error("op={} outcome=retries_exhausted attempts={} elapsedMs={}",
                op, maxAttempts, elapsedMs(start), lastCause);
        return CallOutcome.failure(Failure.RETRIES_EXHAUSTED, maxAttempts, elapsedMs(start));
    }

    private Timer timer(String op, String outcome) {
        return registry.timer("upstream.call.duration", "upstream", op, "outcome", outcome);
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

    /** Capped exponential backoff with FULL jitter, clamped to the remaining budget. */
    private void backoff(String op, int attempt, Deadline deadline) {
        long exp     = policy.baseBackoff().toMillis() << (attempt - 1);
        long capped  = Math.min(exp, policy.maxBackoff().toMillis());
        long jitter  = ThreadLocalRandom.current().nextLong(capped + 1);
        long sleepMs = Math.min(jitter, deadline.remainingMillis());
        if (sleepMs <= 0) return;

        if (log.isDebugEnabled()) {
            log.debug("op={} event=backoff sleepMs={} beforeAttempt={}", op, sleepMs, attempt + 1);
        }
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}