Fifth pass through this machinery, so where a line's reasoning is identical to what we covered on the bootstrap aggregator or the Java 15 port — `nanoTime`, full jitter, MDC restoration, the two-pool rule — I'll state it in a sentence rather than re-derive it. The depth goes to what's new here: `UpstreamException`, the full retry loop, the module wiring, the controller, and **five defects a careful read actually catches**, with patches.

## How the layers stack

```
AggregateController      HTTP shape: status codes, headers, validation
  └─ AggregationService  policy, fan-out, deadline, assembly
      └─ RetryTemplate   attempts, timeouts, backoff, classification, metrics
          └─ UpstreamClient  one blocking call. Nothing else.
```

Each layer knows only the one below. That's why `UpstreamClient` has three methods and no resilience code: adding upstream D inherits everything above it for free.

---

# `Policy` / `Failure`

```java
public enum Policy { WAIT_ALL, FAIL_FAST }
```
An enum, not a boolean `failFast`. `aggregate(req, true)` at a call site is unreadable, and a third policy (`BEST_EFFORT_WITH_CACHE`) would force a signature change. Enums also serialize to stable strings for the header override and the `meta.policy` field.

```java
NONE, NON_RETRYABLE_STATUS, NON_RETRYABLE_ERROR, RETRIES_EXHAUSTED,
DEADLINE_EXCEEDED, ABORTED, REJECTED, INTERRUPTED
```
Eight values, not a boolean `failed`, because **each demands a different response**: `RETRIES_EXHAUSTED` means the upstream is broken, `DEADLINE_EXCEEDED` means it's slow, `REJECTED` means *we're* out of capacity, `ABORTED` means nothing was wrong with this upstream at all. Collapsing them loses exactly the information you need at 3am.

`NONE` for success rather than a nullable failure field: it makes `failure` non-null always, so `failure.name()` never NPEs when tagging a metric.

---

# `UpstreamException`

```java
private final int statusCode;   // 0 == transport-level
```
The retry decision needs to know **why** a call failed, and a bare `IOException` can't say "the server returned 403." Carrying the status lets one predicate classify both transport failures and HTTP failures.

`0` as the sentinel for transport-level rather than `-1` or a nullable `Integer`: it's outside the valid HTTP range, it's the default for an `int`, and it reads naturally in `statusCode == 0 || statusCode >= 500`.

```java
public boolean isRetryable() { return statusCode == 0 || statusCode >= 500 || statusCode == 429; }
```
The three retryable classes, and the reasoning differs for each. **Transport failures** (`0`) are the most likely to be transient — a connection reset says nothing about whether the request was valid. **5xx** is the server admitting fault. **429** is an explicit "try again later," and it's the one people forget: without it, a rate-limited call is treated as a permanent failure and the request degrades when a 200ms wait would have succeeded.

Everything else — 400, 401, 403, 404 — is our request being wrong. Retrying triples load on a call that cannot succeed and burns the deadline arriving at the same answer.

A method on the exception rather than a `switch` in the template, so a client with unusual semantics can override it.

```java
public UpstreamException(String message, Throwable cause) { super(message, cause); this.statusCode = 0; }
```
The two-constructor split is the API telling you which case you're in: status-bearing (HTTP responded) or cause-bearing (it didn't). You can't accidentally construct one with both, which would be meaningless.

---

# `CallOutcome`

```java
public record CallOutcome<T>(T value, Failure failure, int attempts, long elapsedMs) {
```
A record because it's pure data with no behavior beyond derivation — 40 lines of constructor, getters, `equals`, and `hashCode` collapse to one line, and the compiler guarantees they stay consistent.

`attempts` and `elapsedMs` ride along because the aggregator can't reconstruct them: only the template knows a call took three tries and 812ms. Without them the `errors` array in the response says "B failed" instead of "B failed after 3 attempts over 812ms," which is the difference between a report and a diagnosis.

```java
public CallOutcome { Objects.requireNonNull(failure, "failure"); }
```
Compact canonical constructor — validation without restating the four parameters. Only `failure` is checked, because `value` is *legitimately* null on the failure path.

```java
public static <T> CallOutcome<T> success(T value, int attempts, long elapsedMs) {
    return new CallOutcome<>(Objects.requireNonNull(value), Failure.NONE, attempts, elapsedMs);
}

public static <T> CallOutcome<T> failure(Failure failure, int attempts, long elapsedMs) {
    if (failure == Failure.NONE) throw new IllegalArgumentException("NONE is not a failure");
    return new CallOutcome<>(null, failure, attempts, elapsedMs);
}
```
Static factories over the canonical constructor because the two states have **incompatible invariants**: success requires a non-null value and `NONE`; failure requires a null value and not-`NONE`. A single public constructor lets you build `CallOutcome(null, NONE, ...)` — a "successful" outcome carrying no data, which would sail through `isSuccess()` and NPE three layers away. The factories make that unconstructible.

The names also read at the call site: `CallOutcome.failure(Failure.REJECTED, 0, 0)` says what happened without a comment.

```java
public Optional<T> toOptional() { return Optional.ofNullable(value); }
```
The bridge to the assembly code, and it's why `assemble` is `.orElseGet(defaultValue)` instead of a null check per field. `ofNullable` rather than `of` — the whole point is that the value may be absent.

```java
@Override public String toString() {
    return "CallOutcome[" + failure + ", attempts=" + attempts + ", elapsedMs=" + elapsedMs + "]";
}
```
**Overriding the record's generated `toString` specifically to drop `value`.** The default would print the upstream payload — names, addresses, card fragments — into every log line that interpolates an outcome. `toString()` output ends up in logs, exception messages, and debugger frames, all of which get shipped to systems with wider access than the source database.

---

# `Deadline`

Covered in the last turn; the three load-bearing points, compressed:

```java
private final long deadlineNanos;
```
An **absolute instant** so three branch threads and nine attempts can each compute `remaining = deadline - now()` correctly with zero coordination. A decrementing "remaining" field would need synchronization and would drift.

```java
private volatile boolean aborted;
```
`volatile` because it's written by whichever branch fails first and read by two others on different threads. Without it the JVM may hoist the read out of the retry loop and siblings retry forever. Not `AtomicBoolean` — nothing needs compare-and-set, and concurrent writes of `true` are idempotent.

```java
public long remainingMillis() {
    return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
}
```
The `max(0, …)` clamp exists because this feeds `Future.get(timeout, unit)` directly. A negative there happens to mean "don't wait," which is right by accident — clamping makes it right on purpose.

```java
public static Deadline after(Duration budget) { ... }
private Deadline(long deadlineNanos) { ... }
```
Private constructor so nobody can pass a *duration* where an absolute *nano-timestamp* is expected. Two `long`s, catastrophically different meanings, indistinguishable to the compiler — the factory is the only place the unit is named.

---

# `RetryPolicy`

```java
public static final Predicate<Throwable> DEFAULT_RETRYABLE_CAUSE = t -> {
    if (t instanceof UpstreamException ue) return ue.isRetryable();
    return !(t instanceof InterruptedException) && !(t instanceof NullPointerException) && ...;
};
```
A `Predicate` field rather than hard-coded `instanceof` chains in the template, because **this is deployment-specific**. A team using Spring's `RestClientException` or a gRPC `StatusRuntimeException` needs different classification, and they shouldn't have to fork the retry engine to get it.

The `UpstreamException` branch first, since that's the informative case. The fallback is a **deny-list**: retry unless the exception indicates a bug in our own code. `NullPointerException` and `IllegalArgumentException` are deterministic — retrying a guaranteed failure three times just triples the latency of the inevitable.

```java
if (maxAttempts > 30) throw new IllegalArgumentException("maxAttempts > 30 overflows the backoff shift");
```
Guards `baseBackoff << (attempt - 1)` in `backoff()`. Java takes long shift counts mod 64, so `attempt = 65` wraps to a shift of 0 and produces a *tiny* backoff — a silent wrong answer rather than a crash. Nobody sets 65 attempts today, but config gets externalized and someone eventually types a wrong number. Validating at construction is where it's cheap.

```java
requirePositive(perAttemptTimeout, "perAttemptTimeout");
```
Zero or negative would make every `Future.get` time out instantly, producing a service that fails 100% of calls while every log line looks correct.

```java
public static RetryPolicy defaults() {
    return new RetryPolicy(3, Duration.ofMillis(300), Duration.ofMillis(40),
                           Duration.ofMillis(200), DEFAULT_RETRYABLE_CAUSE);
}
```
Five same-typed arguments (three `Duration`s) is a call site where swapping two compiles fine and misbehaves subtly. The named factory means the common path never touches the raw constructor.

**3 attempts:** the second catches most transient blips, the third helps marginally, beyond that you're loading a service that's genuinely down — that's a circuit breaker's job. **300ms per attempt against a 900ms budget:** deliberately generous, because the budget clamp in the template is what actually binds. That lets a single slow call use its full 300ms without pre-reserving room for retries that may never happen.

---

# `DefaultRetryTemplate`

## Setup

```java
final long start = System.nanoTime();
final int maxAttempts = policy.maxAttempts();
final Map<String, String> callerCtx = MDC.getCopyOfContextMap();
Throwable lastCause = null;
```
`start` taken once, before the loop — the reported `elapsedMs` must cover all attempts and backoffs, not just the last one.

`callerCtx` captured on the **branch thread**, before any submission. MDC is thread-local; without this copy every log line emitted from `agg-io-*` loses its correlation id, and those are exactly the retry warnings you need during an incident.

`lastCause` accumulates across attempts so the terminal ERROR can print the actual stack trace — otherwise "retries exhausted" tells you it failed but not why.

## Abort check

```java
if (deadline.isAborted()) {
    registry.counter("upstream.aborted", "upstream", op).increment();
    return CallOutcome.failure(Failure.ABORTED, attempt - 1, elapsedMs(start));
}
```
Top of the loop, before any work. Under FAIL_FAST a sibling has already doomed the request — another 300ms against a possibly-struggling upstream buys nothing.

`attempt - 1` because **this attempt never happened**. Reporting it would inflate retry metrics with work that was never dispatched, and "attempts=3" on a call that made zero HTTP requests is actively misleading.

The counter exists because "how much work does FAIL_FAST waste?" is a real capacity question at 10× traffic, unanswerable without measuring it.

## Deadline clamp

```java
long remaining = deadline.remainingMillis();
if (remaining <= 0) { ...; return CallOutcome.failure(Failure.DEADLINE_EXCEEDED, attempt - 1, elapsedMs(start)); }
long attemptMs = Math.min(policy.perAttemptTimeout().toMillis(), remaining);
```
**The `min` is what turns the SLA from an aspiration into a guarantee.** Two independent constraints, either can bind: attempt 3 starting with 120ms left gets 120ms, not 300ms. Without it, `3 × 300ms + backoffs` reaches ~1.3s against a 900ms budget — the endpoint blows its SLA while every individual timeout looks correctly configured. That's the failure mode that survives code review, because each number is defensible in isolation.

The clock is re-read **every iteration**: time passed during the previous attempt and its backoff.

## Submission

```java
Future<T> f;
try {
    f = io.submit(withMdc(callerCtx, work));
} catch (RejectedExecutionException e) {
    registry.counter("upstream.rejected", "upstream", op).increment();
    return CallOutcome.failure(Failure.REJECTED, attempt - 1, elapsedMs(start));
}
```
`submit` gets its own try block — it must, since the catches below reference `f`, which isn't assigned until it returns.

`RejectedExecutionException` is unchecked, so nothing forces you to handle it, which is exactly why it gets missed. It fires synchronously when the pool is saturated. Uncaught, it escapes `execute()` and breaks the never-throws contract the whole aggregator is built on.

**Returns immediately rather than retrying**, and this is the one case where "give up now" beats "back off." A full pool means every thread is already blocked on this same downstream; retrying adds pressure to the thing that's already failing. Fast load shedding is correct.

## The get

```java
Timer.Sample sample = Timer.start(registry);
T value = f.get(attemptMs, TimeUnit.MILLISECONDS);
sample.stop(timer(op, "success"));
```
**Per-attempt timing, tagged by outcome**, not per-`execute` timing. A call that succeeds on attempt 3 is one 40ms success plus two 300ms timeouts. Recording only the aggregate shows "340ms, success" and hides that the upstream times out two-thirds of the time — which is the single most useful fact during an incident.

`f.get(timeout)` **is** the timeout mechanism, and it's worth being precise about what it does: the blocking HTTP call runs on an `io` thread; this thread waits with a bound. It doesn't make the call faster and it doesn't stop it. It guarantees *this* thread regains control.

```java
if (value == null) {
    log.error("op={} outcome=null_response attempt={} — client contract violated", op, attempt);
    return CallOutcome.failure(Failure.NON_RETRYABLE_ERROR, attempt, elapsedMs(start));
}
```
A client returning null violates its own contract. Without this, null propagates into `CallOutcome.success`, whose `requireNonNull` throws — an NPE from inside the retry template, which is a confusing place to debug a client bug. Catching it here names the culprit.

```java
if (attempt > 1) {
    log.info("op={} outcome=success attempts={} elapsedMs={} recovered=true", ...);
} else if (log.isDebugEnabled()) {
    log.debug("op={} outcome=success attempts=1 elapsedMs={}", ...);
}
```
**Recovered-after-retry is INFO; first-try success is DEBUG.** A clean success is the boring case and logging it at INFO would drown everything else at 500 rps. A recovery is the leading indicator that an upstream is degrading — visible well before it starts failing outright.

The `isDebugEnabled()` guard skips even the argument boxing on the hot path.

## The catches

```java
} catch (TimeoutException e) {
    f.cancel(true);
```
`cancel(true)` interrupts the worker. Without it the abandoned attempt runs forever holding a pool thread and a connection — under sustained timeouts that's a thread leak that poisons the pool for every other request, not just this one. Best-effort: a thread blocked in a raw socket read ignores interrupts, which is why `ServiceAClient` also sets a client-level timeout. Both layers are needed; neither covers the other's gap.

```java
} catch (ExecutionException e) {
    Throwable cause = (e.getCause() != null) ? e.getCause() : e;
    sample.stop(timer(op, "error"));
    if (cause instanceof Error err) {
        log.error("op={} outcome=fatal attempt={} — propagating Error", op, attempt, err);
        throw err;
    }
```
`ExecutionException` wraps whatever the `Callable` threw. **The `Error` re-throw is the one deliberate hole in the never-throws contract**: an OOM or `NoClassDefFoundError` means the JVM is compromised, and degrading gracefully past it returns a plausible partial response while the process dies — then the next request hits the same wall. Better to fail loudly and let the container act.

```java
    if (!policy.retryableCause().test(cause)) {
        Failure kind = (cause instanceof UpstreamException ue && ue.getStatusCode() > 0)
                ? Failure.NON_RETRYABLE_STATUS : Failure.NON_RETRYABLE_ERROR;
        log.error("op={} outcome={} attempt={} elapsedMs={}", op, kind, attempt, elapsedMs(start), cause);
        return CallOutcome.failure(kind, attempt, elapsedMs(start));
    }
```
Splitting `NON_RETRYABLE_STATUS` (upstream said 403) from `NON_RETRYABLE_ERROR` (we threw an NPE) because they route to different people. The first is an integration or auth problem; the second is our bug.

```java
    log.warn("op={} event=attempt_failed attempt={}/{} cause={}: {} action=retry",
            op, attempt, maxAttempts, cause.getClass().getSimpleName(), cause.getMessage());
```
**Class and message at WARN, no stack trace.** Three full traces per failed call is how logs become unreadable and how the one trace that matters gets buried. The full stack appears exactly once, at the terminal ERROR. `action=retry` states the decision, not just the event — a reader shouldn't have to infer what happens next.

```java
} catch (InterruptedException e) {
    f.cancel(true);
    Thread.currentThread().interrupt();
    return CallOutcome.failure(Failure.INTERRUPTED, attempt, elapsedMs(start));
```
Catching `InterruptedException` **clears the interrupt flag as a side effect**. Swallow it and the code above never learns a shutdown was requested — the classic reason a service takes 30 seconds to die and gets `SIGKILL`ed mid-write. Restore the flag, return immediately, don't retry: someone asked us to stop.

```java
} catch (CancellationException e) {
```
Unchecked, thrown by `get()` if the future was cancelled elsewhere (`shutdownNow`). Same class of event as an interrupt, same outcome. Without this catch it would escape as an unchecked exception straight through the never-throws contract.

## Backoff

```java
if (attempt < maxAttempts) {
    registry.counter("upstream.retries", "upstream", op).increment();
    backoff(op, attempt, deadline);
}
```
The guard matters: without it you sleep up to 200ms after the *final* failed attempt and return empty anyway. Pure latency on the path that's already slowest.

`upstream.retries` is the metric that earns its keep — retry rate climbs well before error rate does, so it's the alert that fires while you still have room to act.

```java
long exp     = policy.baseBackoff().toMillis() << (attempt - 1);
long capped  = Math.min(exp, policy.maxBackoff().toMillis());
long jitter  = ThreadLocalRandom.current().nextLong(capped + 1);
long sleepMs = Math.min(jitter, deadline.remainingMillis());
```
Shift instead of `Math.pow` — integer arithmetic, no float rounding, no call in a path that runs on every failure.

**Full jitter** (uniform over `[0, capped]`, not "exponential plus noise"): without it, every client that failed at time T retries at exactly T+40ms, then T+120ms — a synchronized herd that re-kills the upstream the instant it recovers, in a self-sustaining cycle. `ThreadLocalRandom` over `Random` because a shared `Random` CASes one seed from every thread, contending precisely during the retry storm this exists to survive.

`capped + 1` because `nextLong(bound)` is exclusive, and it keeps the bound ≥ 1 (`nextLong(0)` throws).

The final `min(jitter, remaining)` means **even the sleep respects the budget** — otherwise a 200ms nap could push past the deadline, and the next iteration exits immediately. You'd have slept purely to miss the deadline.

---

# `UpstreamClient` and the DTOs

```java
String name();
```
Becomes a **metric tag**, a **log field**, and a **JSON key**. All three are contracts with things outside this codebase — dashboards, alert rules, client parsers. Deriving it from `getClass().getSimpleName()` means a rename silently breaks a dashboard. An explicit method makes the coupling visible.

```java
T fetch(AggregationRequest request) throws Exception;
```
`throws Exception` deliberately wide: the client author shouldn't wrap `IOException` in a custom type just to satisfy a narrow signature. The template classifies whatever emerges, so breadth costs nothing.

```java
T defaultValue();
```
On the client, not the aggregator. The team owning upstream B knows what an empty B is; the aggregator does not. Centralizing defaults would mean the aggregator inventing domain values for services it doesn't own — the path that ends with fabricated data served as real.

```java
public record AggregationRequest(String userId, String correlationId, Policy policyOverride) {}
```
`policyOverride` nullable, meaning "use the configured default." It exists for canaries: shift 5% of traffic to FAIL_FAST via a header and compare degradation rates without a deploy.

```java
public record AggregateResponse(String correlationId, ResultStatus status,
                                AggregateData data, List<UpstreamError> errors, Meta meta) {}
```
`correlationId` **in the body**, not just the header. A user pasting a screenshot of a failed response into a ticket gives you the trace id for free.

`errors` as a list, always present (empty on success), so clients parse one shape unconditionally.

```java
public record UpstreamError(String upstream, String reason, int attempts, long elapsedMs, boolean retryable) {}
```
`retryable` is the field that makes this actionable: it tells the *caller* whether retrying the whole aggregate could plausibly succeed, without them having to know our `Failure` taxonomy.

`reason` as a `String` (the enum name) rather than the enum — the JSON contract stays stable if the enum is refactored internally.

---

# `DefaultAggregationService`

```java
private record Branch<T>(UpstreamClient<T> client, CompletableFuture<CallOutcome<T>> future) {}
```
Pairs the future with the client that produced it. That's what lets `awaitFailFast` take `List<Branch<?>>` (it only touches `future()`) while `assemble` keeps the typed `Branch<AData>` so `data.a` is an `AData` needing no cast. Without the pairing you'd need parallel lists kept in sync by convention, and `harvest` couldn't reach `client.defaultValue()`.

```java
final AggregationConfig cfg = config.get();
final Policy policy = (req.policyOverride() != null) ? req.policyOverride() : cfg.policy();
final Deadline deadline = Deadline.after(cfg.overallBudget());
```
**Snapshot once, at entry.** `config` is a `Supplier` so it can be hot-reloaded — which means reading it twice in one request could yield two different policies, and you'd wait under WAIT_ALL then assemble under FAIL_FAST. The local `policy` is threaded through every method that needs it rather than being re-read.

`Deadline` created first, before any work, so the budget covers submission and queueing too — usually microseconds, but under pool saturation that's exactly the time you most need counted.

```java
final boolean ownsMdc = MDC.get(CORRELATION_ID) == null;
if (ownsMdc) MDC.put(CORRELATION_ID, cid);
```
Only remove what we put. If a servlet filter already set an id, clearing it in our `finally` blinds every log line emitted after this method returns.

```java
Branch<AData> a = launch(clientA, req, deadline);
Branch<BData> b = launch(clientB, req, deadline);
Branch<CData> c = launch(clientC, req, deadline);
```
`supplyAsync` returns immediately, so by line three A and B are already in flight. That's the whole concurrency requirement — wall clock is `max(A,B,C)`.

Three explicit typed fields rather than a loop over `List<UpstreamClient<?>>`: the response DTO is typed, and a loop erases to `Object` and forces casts in `assemble`. With a fixed set of three, explicit wins.

## `launch`

```java
return new Branch<>(client, CompletableFuture.supplyAsync(() -> {
    Map<String, String> prev = MDC.getCopyOfContextMap();
    if (ctx != null) MDC.setContextMap(ctx);
    try { return retry.execute(client.name(), dl, () -> client.fetch(req)); }
    finally { if (prev != null) MDC.setContextMap(prev); else MDC.clear(); }
}, fanOut));
```
The `finally` **restores** rather than clearing, because pool threads are reused — leaving this request's id on the thread mislabels the next request's logs, which is worse than having no logs.

Doubly-nested lambda: outer is the branch (runs on `fanOut`, contains the whole retry loop), inner is one attempt (may run three times). Different lifetimes, hence two.

`fanOut` passed explicitly — the one-arg `supplyAsync` uses `ForkJoinPool.commonPool()`, sized for CPU work and shared JVM-wide. Parking three blocking HTTP calls there starves every parallel stream in the process.

```java
} catch (RejectedExecutionException e) {
    return new Branch<>(client, CompletableFuture.completedFuture(
            CallOutcome.failure(Failure.REJECTED, 0, 0)));
}
```
`supplyAsync` calls `execute()` synchronously, so rejection lands on the HTTP thread, not in the future. Returning an **already-completed** future rather than null keeps everything downstream uniform — `awaitAll`, `harvest`, and `assemble` see a normal future carrying a normal failure. Routing the exceptional path through the same channel as the normal one is the difference between three lines and a fork through the whole assembly.

## `awaitAll`

```java
CompletableFuture.allOf(futures(branches)).get(dl.remainingMillis(), TimeUnit.MILLISECONDS);
```
`allOf` is the barrier; the **timed** `get` is the outer guarantee that the HTTP thread returns on time regardless of branch behavior.

```java
} catch (TimeoutException e) {
    dl.abort();
    log.warn("event=deadline_exceeded policy=WAIT_ALL pending={}", ...);
}
```
**Caught, not rethrown — that is the entire semantic of WAIT_ALL.** The deadline arriving isn't an error, it's the signal to stop waiting and serve what's ready. Rethrowing turns a good two-thirds response into a 504.

Logging the **pending** names (from `!future.isDone()`) tells you which upstream blew the budget. "Deadline exceeded" alone sends you to check all three.

## `awaitFailFast`

```java
CompletableFuture<Void> firstFailure = new CompletableFuture<>();
for (Branch<?> br : branches) {
    br.future().thenAccept(outcome -> {
        if (!outcome.isSuccess()) firstFailure.complete(null);
    });
}
```
**Why not just `anyOf(branches)`:** `anyOf` fires on the first *completion*, and a fast success is a completion — it would abort the request the moment A returned successfully. The predicate "completed **and** failed" is exactly what `anyOf` can't express, so it goes in a callback that completes a manually-managed signal future.

`complete(null)` returns `false` if already completed, so simultaneous failures are harmless. No lock, no CAS, no check-then-act race.

```java
CompletableFuture.anyOf(CompletableFuture.allOf(futures(branches)), firstFailure)
                 .get(dl.remainingMillis(), TimeUnit.MILLISECONDS);
```
**The line the whole policy reduces to.** Two outcomes race — *everything succeeded* or *something failed* — and the timed `get` caps both. The naive version (`allOf().get()` then inspect) is correct but pays the **slowest** branch's latency even when the fastest failed 700ms earlier. That's the difference between `totalMs: 214` and `totalMs: 900` in the example response.

Worth tracing one path: if a branch completes exceptionally (an `Error` escaped), `thenAccept` never runs and `firstFailure` never fires. Not a hang — `allOf` completes exceptionally, `anyOf` propagates, `.get()` throws `ExecutionException` into the catch below. A signal future that could silently never complete is exactly how a request wedges, so it's worth verifying rather than assuming.

```java
} finally {
    dl.abort();
    for (Branch<?> br : branches) br.future().cancel(true);
}
```
`finally` so it runs on every exit. On the success path it's a no-op — completed futures ignore `cancel`, and the `Deadline` is per-request and about to be discarded. Idempotent cleanup in one place beats duplicating it into three catch blocks.

**The caveat that makes `Deadline.abort()` necessary at all:** `CompletableFuture.cancel(true)` does **not** interrupt the thread running a `supplyAsync` task — `mayInterruptIfRunning` has no effect there, per its own Javadoc. It only marks the future cancelled. Real cancellation has to be cooperative, which is why the template checks `isAborted()` before each attempt. One already-dispatched attempt still runs to completion and is discarded, bounded by its per-call timeout. Writing `cancel(true)` and believing the work stops is one of the most common `CompletableFuture` misconceptions.

## `harvest` and `assemble`

```java
if (f.isDone() && !f.isCompletedExceptionally()) {
    return f.getNow(CallOutcome.failure(Failure.DEADLINE_EXCEEDED, 0, 0));
}
```
**Non-blocking by design.** The waiting phase is over and the deadline may already have passed; a blocking `join()` here would let a straggler extend the request past its budget, defeating everything above.

```java
CallOutcome<AData> oa = harvest(a);
CallOutcome<BData> ob = harvest(b);
CallOutcome<CData> oc = harvest(c);
```
Harvest all three **first**. Status, data, and errors are three views of the same snapshot, so they cannot disagree — no chance of reporting `OK` with a populated `errors` array.

```java
if (errors.isEmpty())                 status = ResultStatus.OK;
else if (policy == Policy.FAIL_FAST)  status = ResultStatus.FAILED;
else                                  status = ResultStatus.PARTIAL;
```
The **only** place policy touches the response shape. Ordering matters: `isEmpty()` first, so a successful FAIL_FAST request isn't routed into the failure branch.

```java
AggregateData data = (status == ResultStatus.FAILED) ? null : new AggregateData(
        oa.toOptional().orElseGet(a.client()::defaultValue), ...);
```
`null` under FAILED, deliberately. Emitting three defaults next to `status: FAILED` invites a client to read them as real — undermining the exact thing FAIL_FAST was asked to guarantee.

`orElseGet`, not `orElse`: lazy, so `defaultValue()` is invoked only when the value is absent rather than on every successful request.

```java
long totalMs = sample.stop(registry.timer("aggregate.request.duration",
        "policy", policy.name(), "status", status.name())) / 1_000_000;
```
Stopped **after** status is known, because status is a tag. `p99` for `status=OK` and `status=PARTIAL` are always different distributions — partials are the ones that ran to the deadline — and averaging them hides both.

Reusing the returned nanos for the response body rather than re-reading the clock keeps the reported latency identical to the recorded metric. Otherwise a dashboard and a payload disagree by a few hundred microseconds and someone files a bug.

```java
registry.counter("aggregate.degraded", "upstream", err.upstream(), "reason", err.reason()).increment();
```
Tagged by **both**. "Degradation is up" isn't actionable; "B is degrading via `RETRIES_EXHAUSTED` while C is degrading via `DEADLINE_EXCEEDED`" points at two different fixes — B is broken, C is slow.

```java
return new AggregateResponse(cid, status, data, List.copyOf(errors), new Meta(...));
```
`List.copyOf` makes the response immutable and severs it from the mutable `ArrayList` used during assembly.

---

# `NamedThreadFactory` and `AggregationModule`

```java
private final AtomicInteger counter = new AtomicInteger();
```
`newThread` is called from whichever thread is submitting, and a pool ramping up under load calls it concurrently. A plain `int++` is read-modify-write and would hand two threads the same name.

```java
t.setDaemon(true);
```
Non-daemon threads keep the JVM alive. A forgotten shutdown then means "process hangs forever with no error" instead of "process exits, possibly mid-request" — the second is diagnosable, the first gets misfiled as a deploy bug.

```java
t.setUncaughtExceptionHandler((thread, ex) -> log.error("event=uncaught_exception thread={}", thread.getName(), ex));
```
Rarely fires for `submit()` (exceptions land in the `Future`), but it's the only net under an `Error` or anything routed via `execute()`. Without it a thread dies silently and the pool just gets smaller.

```java
this.fanOut = newElasticPool("agg-fanout", 384);
this.io     = newElasticPool("agg-io", 384);
```
**Two pools, and this is the most important line in the file.** A `fanOut` thread blocks waiting on an `io` task. Share one bounded pool and the three branch threads occupy every slot while the attempts they await queue behind them — deadlock, unresolvable until the timeouts fire, at which point everything fails at once.

The `384` comes from Little's Law: 500 rps × ~200ms mean latency ≈ 100 in-flight requests, each holding 3 `fanOut` + 3 `io` threads ≈ 300 of each at steady state, plus headroom for p99. Getting this wrong in the obvious direction (96, say) means constant `REJECTED` at normal load, showing up as a baseline degradation rate nobody can explain.

```java
return new ThreadPoolExecutor(0, max, 60L, TimeUnit.SECONDS,
        new SynchronousQueue<>(), new NamedThreadFactory(name),
        new ThreadPoolExecutor.AbortPolicy());
```
Every argument is a decision:

- **`corePoolSize = 0`** — no threads retained when idle. This is request-driven; 768 threads sitting overnight is 768 wasted stacks.
- **`SynchronousQueue`** — zero capacity; `offer` succeeds only if a thread is already waiting. Combined with `ThreadPoolExecutor`'s ordering (core → queue → new thread → reject), the effect is **grow to max, then reject**. Swap in `LinkedBlockingQueue` (what `newFixedThreadPool` uses) and it inverts: queue unboundedly, latency climbs invisibly, eventual OOM — all while the pool reports itself healthy. Fast visible rejection beats slow invisible collapse.
- **`AbortPolicy`, and specifically not `CallerRunsPolicy`** — this deserves its own sentence, because `CallerRunsPolicy` is the usual right answer for backpressure and it is **catastrophically wrong here**. It runs the rejected task inline on the calling thread, so `io.submit(work)` would execute the whole HTTP call before returning, and `f.get(attemptMs)` would find an already-completed future. **The 300ms deadline would silently not be enforced** — under saturation, precisely when you most need it.

```java
shutdown("fanOut", fanOut);
shutdown("io", io);
```
`fanOut` first: branch threads depend on `io` threads, so killing `io` first strands branches waiting on futures that can never complete. Reverse dependency order.

```java
es.shutdown();
if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
    log.warn("pool={} event=forced_shutdown abandonedTasks={}", name, es.shutdownNow().size());
}
```
`shutdown()` is graceful but **does not block** — which is why people think it isn't working. `awaitTermination` is the actual wait; `shutdownNow()` is the escalation. In-flight requests get a chance to finish, but a wedged thread can't hold shutdown hostage forever. Logging `abandonedTasks` tells you whether the 5s grace period is enough.

---

# `AggregateController`

```java
@RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
@RequestHeader(value = "X-Aggregation-Policy", required = false) Policy policyOverride
```
`required = false` on both: an inbound id is propagated if present, minted otherwise. Spring converts the header string to the `Policy` enum automatically and returns 400 on a bad value, so no manual parsing.

```java
return ResponseEntity.status(httpStatus(body))
        .header("X-Correlation-Id", body.correlationId())
        .body(body);
```
Correlation id echoed in the **header** as well as the body, so a client can log it without parsing JSON — which matters when the failure is a parse failure.

```java
if (r.status() != ResultStatus.FAILED) return HttpStatus.OK;
```
**PARTIAL is 200, not 206 or 207.** `206 Partial Content` is defined for byte ranges and confuses caches and proxies; `207 Multi-Status` is WebDAV and unsupported by most client libraries. A `200` with `status: PARTIAL` and a populated `errors` array is machine-readable, cache-safe, and won't be blindly retried by a client's generic 5xx handler.

The trade-off is real — a client checking only the status code misses the degradation — which is why `meta.degraded` exists and why the caller-side metric matters.

```java
boolean allRejected = ... allMatch(REJECTED) → 503
boolean anyDeadline = ... anyMatch(DEADLINE_EXCEEDED) → 504
otherwise → 502
```
Three distinct codes because they mean different things **to the caller**. `503` says "we're out of capacity, back off and retry" — and it's the only one where a `Retry-After` header makes sense. `504` says "an upstream was too slow." `502` says "an upstream is broken." A client's retry logic branches on exactly this distinction.

`allMatch` for rejection (if *everything* was rejected, the problem is us) versus `anyMatch` for deadline (any timeout makes the whole request a timeout).

```java
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<String> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(e.getMessage());
}
```
The blank-`userId` guard in the service throws `IllegalArgumentException`; without this handler it becomes a 500. A caller error must return 4xx, or your error-rate alert fires for someone else's bug.

---

# `ServiceAClient`

```java
this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
```
One `HttpClient` per client instance, reused across calls — it owns the connection pool, and constructing one per request means a new TCP handshake (and TLS negotiation) every time.

`connectTimeout` is the layer the template **cannot** provide: `Future.cancel(true)` can't unblock a thread stuck in a TCP connect or TLS handshake, because those aren't interruptible. Only the client can bound them.

```java
.timeout(Duration.ofMillis(300))
```
Belt and braces with the template's per-attempt deadline. The template guarantees the *caller* returns; this guarantees the *worker thread* is actually released rather than lingering as an orphan holding a connection.

```java
if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
    throw new UpstreamException("A returned " + resp.statusCode(), resp.statusCode());
}
```
The client's whole job in one line: turn a non-2xx into a classified exception. It makes no retry decision — it reports the status and lets `RetryPolicy` decide. That's what keeps retry behavior uniform across all three upstreams.

```java
@Override public AData defaultValue() { return new AData(null, "UNKNOWN"); }
```
`"UNKNOWN"` rather than `"FREE"` or `"BASIC"`. A default must be **recognizably absent**, never a plausible real value — a tier of `"FREE"` renders as a real answer and silently changes behavior downstream. This is the single easiest place to cause a production incident with a well-meaning default.

---

# What a careful read catches

Five real defects, in order of impact.

**1. Cancelled siblings are mislabelled, and the example JSON is wrong.** Under FAIL_FAST, `awaitFailFast` cancels the siblings in its `finally`. `harvest` then sees `isCompletedExceptionally() == true` and reports `DEADLINE_EXCEEDED`. So a request where B returned 403 produces **three** errors — B's real one plus two spurious `DEADLINE_EXCEEDED` entries — not the single error my example response showed. Worse, `httpStatus` sees `anyDeadline` and returns **504 instead of 502**.

```java
private static <T> CallOutcome<T> harvest(Branch<T> br) {
    CompletableFuture<CallOutcome<T>> f = br.future();
    if (f.isCancelled()) return CallOutcome.failure(Failure.ABORTED, 0, 0);   // ← add
    if (f.isDone() && !f.isCompletedExceptionally()) {
        return f.getNow(CallOutcome.failure(Failure.DEADLINE_EXCEEDED, 0, 0));
    }
    return CallOutcome.failure(Failure.DEADLINE_EXCEEDED, 0, 0);
}
```
and in `collectError`, skip `ABORTED` under FAIL_FAST — a branch we killed ourselves isn't an upstream failure and shouldn't appear as one.

I removed that `isCancelled()` check last turn as redundant. It *is* redundant for the boolean guard, but not for the classification — which is the actual bug.

**2. A null payload is timed as a success.** `sample.stop(timer(op, "success"))` runs before the null check, so `upstream.call.duration{outcome=success}` counts a call we then classify as `NON_RETRYABLE_ERROR`. Move the null check above the `stop`, or tag it `"invalid"`.

**3. `ServiceAClient`'s 300ms is hard-coded** and duplicates `RetryPolicy.perAttemptTimeout`. Tune one and they drift, and the client silently becomes the binding constraint. Pass the policy's value into the client's constructor.

**4. Unknown `RuntimeException`s are retried.** `DEFAULT_RETRYABLE_CAUSE` denies a specific list and retries everything else, so a deterministic bug in a client's parsing code gets three attempts and burns the deadline. Defensible (many HTTP libraries wrap 5xx in custom unchecked types, and an allow-list would miss them), but it's a choice worth stating rather than inheriting.

**5. No circuit breaker.** After ten minutes of upstream downtime, every request still spends 3 attempts and most of its budget discovering the same thing. Retries are the wrong tool for a sustained outage — and at 10× traffic the retry amplification is what turns one degraded upstream into an outage. The pairing I'd add is a sliding-window breaker **plus** a retry budget (token bucket capping retries at ~10% of requests), because the breaker alone reacts too slowly at the start of an incident.