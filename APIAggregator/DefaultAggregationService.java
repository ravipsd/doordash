package com.example.aggregation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class DefaultAggregationService implements AggregationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAggregationService.class);
    private static final String CORRELATION_ID = "correlationId";

    private final UpstreamClient<AData> clientA;
    private final UpstreamClient<BData> clientB;
    private final UpstreamClient<CData> clientC;
    private final RetryTemplate retry;
    private final ExecutorService fanOut;
    private final MeterRegistry registry;
    private final Supplier<AggregationConfig> config;   // Supplier so hot-reload is possible

    /** Pairs a future with the client that produced it, so harvest/assemble stay generic. */
    private record Branch<T>(UpstreamClient<T> client, CompletableFuture<CallOutcome<T>> future) {}

    public DefaultAggregationService(UpstreamClient<AData> clientA,
                                     UpstreamClient<BData> clientB,
                                     UpstreamClient<CData> clientC,
                                     RetryTemplate retry,
                                     ExecutorService fanOut,
                                     MeterRegistry registry,
                                     Supplier<AggregationConfig> config) {
        this.clientA = Objects.requireNonNull(clientA);
        this.clientB = Objects.requireNonNull(clientB);
        this.clientC = Objects.requireNonNull(clientC);
        this.retry = Objects.requireNonNull(retry);
        this.fanOut = Objects.requireNonNull(fanOut);
        this.registry = Objects.requireNonNull(registry);
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public AggregateResponse aggregate(AggregationRequest req) {
        Objects.requireNonNull(req, "request");
        if (req.userId() == null || req.userId().isBlank()) {
            throw new IllegalArgumentException("userId must not be null or blank");
        }

        // Snapshot once: config may be hot-reloaded, and a policy change mid-request would
        // let us wait under one policy and assemble under another.
        final AggregationConfig cfg = config.get();
        final Policy policy = (req.policyOverride() != null) ? req.policyOverride() : cfg.policy();
        final Deadline deadline = Deadline.after(cfg.overallBudget());

        final String cid = (req.correlationId() != null && !req.correlationId().isBlank())
                ? req.correlationId() : UUID.randomUUID().toString();
        final boolean ownsMdc = MDC.get(CORRELATION_ID) == null;   // don't clobber an upstream filter's id
        if (ownsMdc) MDC.put(CORRELATION_ID, cid);

        Timer.Sample sample = Timer.start(registry);
        try {
            log.info("event=aggregate_start policy={} budgetMs={}", policy, cfg.overallBudget().toMillis());

            // All three in flight before anything is awaited: wall clock is max(A,B,C), not the sum.
            Branch<AData> a = launch(clientA, req, deadline);
            Branch<BData> b = launch(clientB, req, deadline);
            Branch<CData> c = launch(clientC, req, deadline);
            List<Branch<?>> all = List.of(a, b, c);

            if (policy == Policy.FAIL_FAST) awaitFailFast(all, deadline);
            else                            awaitAll(all, deadline);

            return assemble(cid, policy, a, b, c, sample);
        } finally {
            if (ownsMdc) MDC.remove(CORRELATION_ID);
        }
    }

    // ---------------------------------------------------------------- launch

    private <T> Branch<T> launch(UpstreamClient<T> client, AggregationRequest req, Deadline dl) {
        Map<String, String> ctx = MDC.getCopyOfContextMap();
        try {
            return new Branch<>(client, CompletableFuture.supplyAsync(() -> {
                Map<String, String> prev = MDC.getCopyOfContextMap();
                if (ctx != null) MDC.setContextMap(ctx);
                try {
                    return retry.execute(client.name(), dl, () -> client.fetch(req));
                } finally {
                    if (prev != null) MDC.setContextMap(prev); else MDC.clear();
                }
            }, fanOut));
        } catch (RejectedExecutionException e) {
            // supplyAsync calls execute() synchronously — rejection lands here, not in the future.
            log.error("op={} event=branch_rejected reason=fanout_saturated_or_shutdown", client.name());
            return new Branch<>(client, CompletableFuture.completedFuture(
                    CallOutcome.failure(Failure.REJECTED, 0, 0)));
        }
    }

    // ---------------------------------------------------------------- waiting

    /** WAIT_ALL: the deadline arriving is a signal to stop waiting, not an error. */
    private void awaitAll(List<Branch<?>> branches, Deadline dl) {
        try {
            CompletableFuture.allOf(futures(branches)).get(dl.remainingMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            dl.abort();   // stragglers stop retrying instead of burning pool threads
            log.warn("event=deadline_exceeded policy=WAIT_ALL pending={}",
                    branches.stream().filter(br -> !br.future().isDone())
                            .map(br -> br.client().name()).toList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // Branches return CallOutcome rather than throwing; reaching here is our own bug.
            log.error("event=branch_failed_unexpectedly", e.getCause());
        }
    }

    /**
     * FAIL_FAST: races "everything succeeded" against "something failed".
     * anyOf(branches) alone is wrong — it fires on the first *completion*, and a fast
     * success would abort the request.
     */
    private void awaitFailFast(List<Branch<?>> branches, Deadline dl) {
        CompletableFuture<Void> firstFailure = new CompletableFuture<>();
        for (Branch<?> br : branches) {
            br.future().thenAccept(outcome -> {
                if (!outcome.isSuccess()) firstFailure.complete(null);   // complete() is idempotent
            });
        }
        try {
            CompletableFuture.anyOf(CompletableFuture.allOf(futures(branches)), firstFailure)
                             .get(dl.remainingMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("event=deadline_exceeded policy=FAIL_FAST");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // A branch completed exceptionally (Error escaped), so thenAccept never fired.
            log.error("event=branch_failed_unexpectedly", e.getCause());
        } finally {
            // No-op on the success path (completed futures ignore cancel). Note cancel(true) does
            // NOT interrupt a supplyAsync task — abort() is what actually stops sibling retries.
            dl.abort();
            for (Branch<?> br : branches) br.future().cancel(true);
        }
    }

    private static CompletableFuture<?>[] futures(List<Branch<?>> branches) {
        return branches.stream().map(Branch::future).toArray(CompletableFuture<?>[]::new);
    }

    // ---------------------------------------------------------------- assembly

    /** Non-blocking: the waiting phase is over, so a straggler must not extend the request. */
    private static <T> CallOutcome<T> harvest(Branch<T> br) {
        CompletableFuture<CallOutcome<T>> f = br.future();
        if (f.isDone() && !f.isCompletedExceptionally()) {
            return f.getNow(CallOutcome.failure(Failure.DEADLINE_EXCEEDED, 0, 0));
        }
        return CallOutcome.failure(Failure.DEADLINE_EXCEEDED, 0, 0);
    }

    private AggregateResponse assemble(String cid, Policy policy,
                                       Branch<AData> a, Branch<BData> b, Branch<CData> c,
                                       Timer.Sample sample) {
        // One snapshot, three views (status / data / errors) — they cannot disagree.
        CallOutcome<AData> oa = harvest(a);
        CallOutcome<BData> ob = harvest(b);
        CallOutcome<CData> oc = harvest(c);

        List<UpstreamError> errors = new ArrayList<>();
        collectError(a.client(), oa, errors);
        collectError(b.client(), ob, errors);
        collectError(c.client(), oc, errors);

        ResultStatus status;
        if (errors.isEmpty())                 status = ResultStatus.OK;
        else if (policy == Policy.FAIL_FAST)  status = ResultStatus.FAILED;
        else                                  status = ResultStatus.PARTIAL;

        // Null under FAILED: emitting defaults alongside a failure invites the caller to read
        // them as real data, which is exactly what FAIL_FAST asked us not to do.
        AggregateData data = (status == ResultStatus.FAILED) ? null : new AggregateData(
                oa.toOptional().orElseGet(a.client()::defaultValue),
                ob.toOptional().orElseGet(b.client()::defaultValue),
                oc.toOptional().orElseGet(c.client()::defaultValue));

        long totalMs = sample.stop(registry.timer("aggregate.request.duration",
                "policy", policy.name(), "status", status.name())) / 1_000_000;

        for (UpstreamError err : errors) {
            registry.counter("aggregate.degraded", "upstream", err.upstream(), "reason", err.reason()).increment();
        }
        log.info("event=aggregate_complete status={} degraded={} totalMs={} failed={}",
                status, !errors.isEmpty(), totalMs,
                errors.stream().map(UpstreamError::upstream).toList());

        return new AggregateResponse(cid, status, data, List.copyOf(errors),
                new Meta(totalMs, policy.name(), !errors.isEmpty()));
    }

    private static <T> void collectError(UpstreamClient<T> client, CallOutcome<T> o, List<UpstreamError> out) {
        if (o.isSuccess()) return;
        out.add(new UpstreamError(client.name(), o.failure().name(),
                o.attempts(), o.elapsedMs(), isRetryable(o.failure())));
    }

    /** Tells the caller whether retrying the whole aggregate could plausibly succeed. */
    private static boolean isRetryable(Failure f) {
        return switch (f) {
            case RETRIES_EXHAUSTED, DEADLINE_EXCEEDED, REJECTED, ABORTED -> true;
            default -> false;
        };
    }
}