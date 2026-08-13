This is the first problem in this thread generalized: three siblings instead of a chain, plus an HTTP surface, a policy switch, and observability. Rather than rewrite what we already built, I'll reuse `CallOutcome`, the retry loop, and the two-pool executor design from earlier, and spend the space on what's genuinely new — **FAIL_FAST short-circuiting, the request-wide deadline, and the metrics surface.**

## Assumptions

Stated up front since the prompt leaves these open:

| Unspecified | Assumption |
|---|---|
| Upstream semantics | All three are idempotent reads (`GET`). Retries are safe. |
| Independence | A, B, C have no data dependency — true fan-out, unlike the earlier bootstrap chain. |
| Scale | ~500 rps steady, p99 upstream ~400ms, SLA 1s end-to-end. |
| Defaults | Each upstream has a defined "empty" value (`null` object / empty list), not a fabricated one. |
| Auth/security | Handled by a gateway ahead of this service; out of scope. |
| Caching | Not required for the functional path; introduced only as a degradation lever. |
| Language | Java 17 (records for DTOs). The Java 15 variant is the hand-written class style from earlier in this thread. |

**Non-goals:** write paths, cross-request consistency, multi-region failover, response caching as a primary latency strategy.

**Success metrics:** p99 `/aggregate` ≤ 1s; ≥99.9% of requests return 2xx *or* a well-formed partial; zero unbounded retry amplification during an upstream outage.

---

# Deliverable 1 — Interfaces

## Client

```java
/** One upstream. Implementations do a single blocking HTTP call — no retry, no timeout logic. */
public interface UpstreamClient<T> {
    /** Stable id used for metric tags, log fields, and error payload keys. */
    String name();

    /** One attempt. May throw; the RetryTemplate classifies and decides. */
    T fetch(AggregationRequest request) throws Exception;

    /** Value substituted when this upstream fails under WAIT_ALL. */
    T defaultValue();
}
```

The client is deliberately **dumb**: no retries, no timeouts, no metrics. Every cross-cutting concern lives in the template, so adding upstream D is one class with three methods.

## Deadline

```java
/** Request-wide budget, shared by all branches. Distinct from per-call timeout. */
public final class Deadline {
    private final long deadlineNanos;
    private volatile boolean aborted;

    public static Deadline after(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos());
    }
    private Deadline(long deadlineNanos) { this.deadlineNanos = deadlineNanos; }

    public long remainingMillis() {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }
    public boolean expired() { return remainingMillis() <= 0; }

    /** Cooperative cancellation: FAIL_FAST sets this so sibling branches stop retrying. */
    public void abort() { aborted = true; }
    public boolean isAborted() { return aborted; }
}
```

`volatile` because it's written by whichever branch fails first and read by the other two on different threads.

## Retry template

```java
public interface RetryTemplate {
    /**
     * Runs {@code work} with a per-attempt timeout and capped exponential backoff with jitter,
     * never exceeding {@code deadline}. Never throws — every failure is a CallOutcome.Failure.
     * Errors propagate by design.
     */
    <T> CallOutcome<T> execute(String op, Deadline deadline, Callable<T> work);
}
```

```java
public enum Failure {
    NONE, NON_RETRYABLE_STATUS, NON_RETRYABLE_ERROR, RETRIES_EXHAUSTED,
    DEADLINE_EXCEEDED,   // request budget gone
    ABORTED,             // sibling failed under FAIL_FAST
    REJECTED,            // thread pool saturated — load shed
    INTERRUPTED
}
```

## Service

```java
public interface AggregationService {
    AggregateResponse aggregate(AggregationRequest request);
}

public enum Policy { WAIT_ALL, FAIL_FAST }
```

---

# Retry template implementation

This is the `ResilientCaller` from earlier with three deltas. Rather than repeat the loop, here are the changes:

```java
public <T> CallOutcome<T> execute(String op, Deadline deadline, Callable<T> work) {
    long start = System.nanoTime();
    Map<String, String> callerCtx = MDC.getCopyOfContextMap();

    for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {

        // DELTA 1: cooperative abort. Under FAIL_FAST a sibling has already doomed the
        // request — spending another 2s and another upstream call on it is pure waste.
        if (deadline.isAborted()) {
            metrics.counter("upstream.aborted", "upstream", op).increment();
            return CallOutcome.failure(Failure.ABORTED, attempt - 1, elapsedMs(start));
        }

        // DELTA 2: the attempt timeout is bounded by BOTH the per-call config and the
        // request-wide remaining budget, so retries can never overrun the SLA.
        long remaining = deadline.remainingMillis();
        if (remaining <= 0) {
            metrics.counter("upstream.deadline_exceeded", "upstream", op).increment();
            return CallOutcome.failure(Failure.DEADLINE_EXCEEDED, attempt - 1, elapsedMs(start));
        }
        long attemptMs = Math.min(policy.perAttemptTimeout().toMillis(), remaining);

        Future<T> f;
        try {
            f = io.submit(withMdc(callerCtx, work));
        } catch (RejectedExecutionException e) {
            metrics.counter("upstream.rejected", "upstream", op).increment();
            return CallOutcome.failure(Failure.REJECTED, attempt - 1, elapsedMs(start));
        }

        Timer.Sample sample = Timer.start(registry);            // DELTA 3: per-attempt timing
        try {
            T value = f.get(attemptMs, TimeUnit.MILLISECONDS);
            sample.stop(registry.timer("upstream.call.duration", "upstream", op, "outcome", "success"));
            if (attempt > 1) {
                log.info("op={} outcome=success attempts={} recovered=true elapsedMs={}",
                         op, attempt, elapsedMs(start));
            }
            return CallOutcome.success(value, attempt, elapsedMs(start));

        } catch (TimeoutException e) {
            f.cancel(true);
            sample.stop(registry.timer("upstream.call.duration", "upstream", op, "outcome", "timeout"));
            metrics.counter("upstream.timeouts", "upstream", op).increment();
            log.warn("op={} event=attempt_timeout attempt={}/{} timeoutMs={} action=retry",
                     op, attempt, policy.maxAttempts(), attemptMs);

        } catch (ExecutionException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof Error) throw (Error) cause;    // never mask a fatal condition
            sample.stop(registry.timer("upstream.call.duration", "upstream", op, "outcome", "error"));
            if (!policy.retryableCause().test(cause)) {
                log.error("op={} outcome=non_retryable attempt={}", op, attempt, cause);
                return CallOutcome.failure(Failure.NON_RETRYABLE_ERROR, attempt, elapsedMs(start));
            }
            log.warn("op={} event=attempt_failed attempt={}/{} cause={}: {} action=retry",
                     op, attempt, policy.maxAttempts(), cause.getClass().getSimpleName(), cause.getMessage());

        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            return CallOutcome.failure(Failure.INTERRUPTED, attempt, elapsedMs(start));
        }

        if (attempt < policy.maxAttempts()) {
            metrics.counter("upstream.retries", "upstream", op).increment();
            backoff(op, attempt, deadline);   // capped exponential + FULL jitter
        }
    }
    log.error("op={} outcome=retries_exhausted attempts={} elapsedMs={}",
              op, policy.maxAttempts(), elapsedMs(start));
    return CallOutcome.failure(Failure.RETRIES_EXHAUSTED, policy.maxAttempts(), elapsedMs(start));
}
```

Backoff is `min(base × 2^(n-1), cap)` with **full jitter** — uniform over `[0, capped]`, not "exponential plus noise." Without it, every client that failed at time T retries in lockstep and re-kills the upstream the moment it recovers. The sleep is also clamped to `deadline.remainingMillis()`, so backoff can't be what blows the SLA.

---

# Deliverable 2 — Concurrency flow

```
HTTP thread                fanOut pool (branch)         io pool (attempt)
───────────                ────────────────────         ─────────────────
GET /aggregate
  │ deadline = now + 900ms
  │ correlationId → MDC
  ├─ supplyAsync(A) ────────► retry.execute("A") ────────► clientA.fetch()   [≤300ms]
  ├─ supplyAsync(B) ────────► retry.execute("B") ────────► clientB.fetch()   [≤300ms]
  ├─ supplyAsync(C) ────────► retry.execute("C") ────────► clientC.fetch()   [≤300ms]
  │                                │ 500 → backoff(~40ms) ─► retry attempt 2
  │
  ├─ WAIT_ALL:  allOf(A,B,C).get(remaining)  ── deadline hit → harvest whatever is done
  ├─ FAIL_FAST: anyOf(allOf(A,B,C), firstFailure).get(remaining)
  │
  └─ assemble response ON THE HTTP THREAD, after joining → immutable, no lock
```

**Two pools, not one.** A `fanOut` thread blocks waiting on an `io` task. Share one bounded pool and the three branch threads can occupy every slot while the attempts they await sit queued behind them — deadlock under load. Same rule as the bootstrap service earlier: *a pool whose tasks block on tasks in the same pool is a deadlock waiting for traffic.*

**Wall clock is `max`, not `sum`.** Worst case is the overall budget (900ms), never `3 × perCallTimeout × maxAttempts`.

**No shared mutable state.** Each branch returns its own `CallOutcome`; the response is built on the HTTP thread after joining, from locals, into an immutable record. `join()` supplies the happens-before edge. No lock, no `ConcurrentHashMap` — the concurrency is structured so shared state doesn't exist.

## The coordination

```java
private record Branch<T>(UpstreamClient<T> client, CompletableFuture<CallOutcome<T>> future) {}

@Override
public AggregateResponse aggregate(AggregationRequest req) {
    Deadline deadline = Deadline.after(config.overallBudget());
    String cid = (req.correlationId() != null) ? req.correlationId() : UUID.randomUUID().toString();
    boolean ownsMdc = MDC.get(CORRELATION_ID) == null;
    if (ownsMdc) MDC.put(CORRELATION_ID, cid);
    Timer.Sample sample = Timer.start(registry);

    try {
        Branch<AData> a = launch(clientA, req, deadline);
        Branch<BData> b = launch(clientB, req, deadline);
        Branch<CData> c = launch(clientC, req, deadline);
        List<Branch<?>> all = List.of(a, b, c);

        if (config.policy() == Policy.FAIL_FAST) {
            awaitFailFast(all, deadline);
        } else {
            awaitAll(all, deadline);
        }
        return assemble(cid, a, b, c, deadline, sample);
    } finally {
        if (ownsMdc) MDC.remove(CORRELATION_ID);
    }
}

private <T> Branch<T> launch(UpstreamClient<T> client, AggregationRequest req, Deadline dl) {
    Map<String, String> ctx = MDC.getCopyOfContextMap();   // MDC is thread-local — must be carried over
    try {
        return new Branch<>(client, CompletableFuture.supplyAsync(() -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            if (ctx != null) MDC.setContextMap(ctx);
            try {
                return retry.execute(client.name(), dl, () -> client.fetch(req));
            } finally {
                if (prev != null) MDC.setContextMap(prev); else MDC.clear();   // pool threads are reused
            }
        }, fanOut));
    } catch (RejectedExecutionException e) {
        // supplyAsync calls execute() synchronously, so rejection lands here, not in the future.
        log.error("op={} event=branch_rejected reason=fanout_saturated", client.name());
        return new Branch<>(client, CompletableFuture.completedFuture(
                CallOutcome.failure(Failure.REJECTED, 0, 0)));
    }
}
```

### WAIT_ALL

```java
private void awaitAll(List<Branch<?>> branches, Deadline dl) {
    CompletableFuture<?>[] fs = branches.stream().map(Branch::future).toArray(CompletableFuture[]::new);
    try {
        CompletableFuture.allOf(fs).get(dl.remainingMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
        // Budget gone. Do NOT fail — harvest whatever completed; the rest default.
        dl.abort();
        log.warn("event=deadline_exceeded policy=WAIT_ALL pending={}",
                 branches.stream().filter(br -> !br.future().isDone()).map(br -> br.client().name()).toList());
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
        // Branches return CallOutcome rather than throwing; reaching here is our own bug.
        log.error("event=branch_failed_unexpectedly", e.getCause());
    }
}
```

### FAIL_FAST

The genuinely new piece. Waiting for `allOf` and *then* checking for failures isn't fail-fast — it pays the slowest branch's latency regardless.

```java
private void awaitFailFast(List<Branch<?>> branches, Deadline dl) {
    CompletableFuture<Void> firstFailure = new CompletableFuture<>();
    for (Branch<?> br : branches) {
        br.future().thenAccept(outcome -> {
            if (!outcome.isSuccess()) firstFailure.complete(null);   // idempotent; later callers no-op
        });
    }
    CompletableFuture<?>[] fs = branches.stream().map(Branch::future).toArray(CompletableFuture[]::new);

    try {
        // Whichever comes first: everything succeeded, or one thing failed.
        CompletableFuture.anyOf(CompletableFuture.allOf(fs), firstFailure)
                         .get(dl.remainingMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
        log.warn("event=deadline_exceeded policy=FAIL_FAST");
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
        log.error("event=branch_failed_unexpectedly", e.getCause());
    } finally {
        // Cooperative abort stops sibling *retries* immediately.
        dl.abort();
        for (Branch<?> br : branches) br.future().cancel(true);
    }
}
```

**One honest caveat worth raising unprompted:** `CompletableFuture.cancel(true)` does **not** interrupt the thread running a `supplyAsync` task — `mayInterruptIfRunning` has no effect there, per its own Javadoc. That's why `Deadline.abort()` exists: cancellation has to be *cooperative*. The template checks `isAborted()` before each attempt, so no further retries or upstream calls happen, but one already-dispatched attempt may run to completion and be discarded. It's bounded by the per-call timeout, so the waste is capped — and pretending `cancel(true)` reaches through would be the bug.

---

# Deliverable 3 — Error handling and responses

## DTOs

```java
public record AggregateResponse(
        String correlationId,
        ResultStatus status,          // OK | PARTIAL | FAILED
        AggregateData data,
        List<UpstreamError> errors,   // empty when OK
        Meta meta) {}

public record AggregateData(AData a, BData b, CData c) {}

public record UpstreamError(String upstream, String reason, int attempts, long elapsedMs, boolean retryable) {}

public record Meta(long totalMs, String policy, boolean degraded) {}

public enum ResultStatus { OK, PARTIAL, FAILED }
```

## Assembly

```java
private AggregateResponse assemble(String cid, Branch<AData> a, Branch<BData> b, Branch<CData> c,
                                   Deadline dl, Timer.Sample sample) {
    CallOutcome<AData> oa = harvest(a);
    CallOutcome<BData> ob = harvest(b);
    CallOutcome<CData> oc = harvest(c);
    List<UpstreamError> errors = new ArrayList<>();
    collectError(a.client(), oa, errors);
    collectError(b.client(), ob, errors);
    collectError(c.client(), oc, errors);

    ResultStatus status;
    if (errors.isEmpty())                          status = ResultStatus.OK;
    else if (config.policy() == Policy.FAIL_FAST)  status = ResultStatus.FAILED;
    else                                           status = ResultStatus.PARTIAL;

    AggregateData data = (status == ResultStatus.FAILED) ? null : new AggregateData(
            oa.toOptional().orElseGet(a.client()::defaultValue),
            ob.toOptional().orElseGet(b.client()::defaultValue),
            oc.toOptional().orElseGet(c.client()::defaultValue));

    long totalMs = sample.stop(registry.timer("aggregate.request.duration",
            "policy", config.policy().name(), "status", status.name())) / 1_000_000;

    for (UpstreamError err : errors) {
        registry.counter("aggregate.degraded", "upstream", err.upstream(), "reason", err.reason()).increment();
    }
    log.info("event=aggregate_complete status={} degraded={} totalMs={} failed={}",
             status, !errors.isEmpty(), totalMs, errors.stream().map(UpstreamError::upstream).toList());

    return new AggregateResponse(cid, status, data, errors,
            new Meta(totalMs, config.policy().name(), !errors.isEmpty()));
}

/** A branch that never completed (deadline hit) becomes DEADLINE_EXCEEDED, not an exception. */
private static <T> CallOutcome<T> harvest(Branch<T> br) {
    CompletableFuture<CallOutcome<T>> f = br.future();
    if (f.isDone() && !f.isCompletedExceptionally() && !f.isCancelled()) {
        return f.getNow(CallOutcome.failure(Failure.DEADLINE_EXCEEDED, 0, 0));
    }
    return CallOutcome.failure(Failure.DEADLINE_EXCEEDED, 0, 0);
}
```

## Status code mapping

| Situation | HTTP | Body `status` |
|---|---|---|
| All three succeed | `200` | `OK` |
| WAIT_ALL, some failed | `200` | `PARTIAL` |
| FAIL_FAST, upstream failed | `502` | `FAILED` |
| FAIL_FAST or WAIT_ALL, deadline hit with nothing usable | `504` | `FAILED` |
| Fan-out pool saturated (all branches rejected) | `503` + `Retry-After` | `FAILED` |

**Why `200` for partial and not `206`/`207`:** `206 Partial Content` is defined for byte-range responses and confuses caches and proxies; `207 Multi-Status` is WebDAV and unsupported by most client libraries. A `200` with an explicit `status: PARTIAL` and a populated `errors` array is machine-readable, cache-safe, and won't be silently retried by a client's generic 5xx handler. The trade-off — clients that only check the status code will miss the degradation — is mitigated by the `degraded` flag in `meta` and by the caller-side metric.

## Example responses

**All succeed**
```json
{
  "correlationId": "8f2a-...-c41",
  "status": "OK",
  "data": { "a": {"userId":"u-1","tier":"GOLD"}, "b": {"cards":[{"last4":"4242"}]}, "c": {"address":"42 Elm St"} },
  "errors": [],
  "meta": { "totalMs": 312, "policy": "WAIT_ALL", "degraded": false }
}
```

**WAIT_ALL, B exhausted retries, C timed out on the deadline** — `HTTP 200`
```json
{
  "correlationId": "8f2a-...-c41",
  "status": "PARTIAL",
  "data": { "a": {"userId":"u-1","tier":"GOLD"}, "b": {"cards":[]}, "c": {"address":""} },
  "errors": [
    { "upstream":"B", "reason":"RETRIES_EXHAUSTED",  "attempts":3, "elapsedMs":812, "retryable":true },
    { "upstream":"C", "reason":"DEADLINE_EXCEEDED",  "attempts":2, "elapsedMs":900, "retryable":true }
  ],
  "meta": { "totalMs": 903, "policy": "WAIT_ALL", "degraded": true }
}
```
Defaults are the client's declared empty values — `[]` and `""` — never fabricated data.

**FAIL_FAST, B failed at 210ms** — `HTTP 502`
```json
{
  "correlationId": "3d90-...-77b",
  "status": "FAILED",
  "data": null,
  "errors": [ { "upstream":"B", "reason":"NON_RETRYABLE_STATUS", "attempts":1, "elapsedMs":210, "retryable":false } ],
  "meta": { "totalMs": 214, "policy": "FAIL_FAST", "degraded": true }
}
```
Note `totalMs: 214`, not 900 — the short-circuit is doing its job; A and C were aborted mid-flight.

---

# Observability

## Logging

Correlation id enters via `X-Correlation-Id` or is minted, goes into MDC, and — critically — is **carried into the pool threads** by `launch()`. Without that copy, every log line from `fanOut-*` and `io-*` threads is an orphan you can't join to a request.

```
INFO  [cid=8f2a] event=aggregate_start policy=WAIT_ALL budgetMs=900
WARN  [cid=8f2a] op=B event=attempt_failed attempt=1/3 cause=SocketTimeoutException: read timed out action=retry
WARN  [cid=8f2a] op=B event=attempt_timeout attempt=2/3 timeoutMs=300 action=retry
ERROR [cid=8f2a] op=B outcome=retries_exhausted attempts=3 elapsedMs=812
WARN  [cid=8f2a] event=deadline_exceeded policy=WAIT_ALL pending=[C]
INFO  [cid=8f2a] event=aggregate_complete status=PARTIAL degraded=true totalMs=903 failed=[B, C]
```

Conventions: `key=value` so log tooling can facet without regex; parameterized (never concatenated) so disabled levels cost nothing; **stack traces only at the terminal failure**, class+message at per-attempt WARN; and no PII in any field.

## Metrics

| Metric | Type | Tags | Answers |
|---|---|---|---|
| `upstream.call.duration` | Timer | `upstream`, `outcome` | Which upstream is slow, and how |
| `upstream.retries` | Counter | `upstream` | **Leading indicator** — rises before failures do |
| `upstream.timeouts` | Counter | `upstream` | Timeout vs error distinction |
| `upstream.rejected` | Counter | `upstream` | Pool saturation |
| `upstream.aborted` | Counter | `upstream` | Work wasted by FAIL_FAST |
| `aggregate.request.duration` | Timer | `policy`, `status` | SLA compliance |
| `aggregate.degraded` | Counter | `upstream`, `reason` | Partial-response rate by cause |
| `pool.queue.rejections` | Counter | `pool` | Capacity headroom |
| `circuit.state` | Gauge | `upstream` | Breaker open/half-open/closed |

`upstream.retries` is the one that earns its keep: retry rate climbs well before error rate does, so it's the alert that fires while you still have room to act.

## Alerts

- `p99(aggregate.request.duration) > 900ms` for 5m → page
- `rate(aggregate.degraded) / rate(aggregate.request) > 5%` for 10m → page
- `rate(upstream.retries) > 3× 7-day baseline` → warn (early warning)
- `circuit.state == OPEN` for > 2m → page
- `rate(upstream.rejected) > 0` sustained → warn (capacity)

---

# Configuration

```yaml
aggregation:
  policy: WAIT_ALL           # per-deployment; overridable per-request via header for canaries
  overallBudgetMs: 900       # < 1s SLA, leaving headroom for serialization
  perCallTimeoutMs: 300      # 3 * 300 == budget, so a serial worst case still fits
  retry:
    maxAttempts: 3
    baseBackoffMs: 40
    maxBackoffMs: 200        # << budget, so backoff can't eat the deadline
  pools:
    fanOutMax: 96            # >= 3 * peak concurrent requests
    ioMax: 96
  circuitBreaker:
    failureRateThreshold: 50%
    slidingWindow: 100
    openDurationMs: 10000
```

The budget arithmetic is the part to defend: `perCallTimeout × maxAttempts` deliberately **exceeds** the budget, because the budget clamp in the template makes the overall deadline the binding constraint. That's intentional — it lets a single slow call use its full 300ms without reserving room for retries that may not happen.

---

# Trade-offs and failure modes

**What breaks first at 10× traffic.** Thread pools, then retry amplification. At 5,000 rps the pools reject (by design — `SynchronousQueue` + `AbortPolicy` sheds fast rather than queueing unboundedly into an OOM), and rejections surface as `503`s. The nastier failure is second-order: one upstream degrading turns 5,000 rps into 15,000 rps against it, which guarantees it never recovers. Two mitigations, and I'd want both: a **circuit breaker** per upstream, and a **retry budget** — a token bucket capping retries at ~10% of requests, so retry load is bounded by *total traffic* rather than by failure rate. The circuit breaker alone reacts too slowly at the start of an incident.

Beyond that: HTTP connection pool exhaustion (size it to `ioMax`, or connection acquisition becomes an invisible queue *inside* the timeout), then GC pressure from per-request allocation.

**Graceful degradation, in order of preference.** Serve defaults with `status: PARTIAL` (already implemented) → serve stale cache with an explicit `as_of` timestamp → shed load with `503` + `Retry-After`. Crucially, **the caller must be able to tell degraded from healthy** — hence `meta.degraded`. Silently substituting defaults so a dashboard shows green is the failure mode that turns a 20-minute incident into a week of confused debugging.

**WAIT_ALL vs FAIL_FAST is a data-semantics decision, not a preference.** WAIT_ALL suits a dashboard where a missing widget is acceptable. FAIL_FAST suits anything where a partial answer would be acted on as complete — pricing, eligibility, payments. Getting this wrong in the "safe" direction is still wrong: a partial pricing response is worse than an error.

**Hedged requests** are the obvious next lever for tail latency — fire a duplicate at p95 and take the first response, trading ~5% extra upstream load for a much tighter p99. I've left them out because they're only safe on idempotent reads and they interact badly with a retry budget (both consume the same headroom). It's the first thing I'd add if the p99 target tightened.

**Post-launch validation.** Canary on 5% with the policy header, comparing `aggregate.degraded` rate and p99 against baseline; a chaos test injecting 500s and 5s hangs per upstream to verify the deadline actually binds and FAIL_FAST short-circuits (assert `totalMs < 300`, not just that the status is right); and a load test at 3× projected peak to confirm rejection is graceful rather than an OOM.