Going file by file. Where a line is self-evident I group it; where a line encodes a decision that could have gone another way, I spell out the alternative and why it lost.

---

# `HttpResponse` — the failure taxonomy

```java
private final int statusCode;
```
`final` because a response is a record of something that already happened. Making it mutable would let one thread reclassify a response another thread is mid-way through interpreting. Immutable objects are also safe to publish across threads without synchronization, which matters because these cross a thread boundary on every call.

```java
protected HttpResponse(int statusCode) { this.statusCode = statusCode; }
```
`protected`, not `public` — nothing should instantiate a bare `HttpResponse`; it's only ever a superclass. The compiler enforces the intent.

```java
public boolean isSuccess()   { return statusCode >= 200 && statusCode < 300; }
public boolean isRetryable() { return statusCode >= 500; }
```
These two lines are the most consequential in the file, because **every retry decision in the system is delegated to them.**

Why methods on the base class rather than `if (code >= 500)` inside `ResilientCaller`:

- The retry engine stays free of HTTP trivia. It asks "should I try again?" and doesn't care how that's computed.
- A service with a nonstandard contract overrides one method. If `PaymentService` also wants 429 retried, that's `@Override isRetryable() { return statusCode >= 500 || statusCode == 429; }` on `PaymentResponse` — zero changes to the retry loop.

Why `isSuccess()` is `2xx` and not `!isRetryable()`: they're not complements. 3xx is neither a success nor retryable. Defining them independently leaves a deliberate gap in the middle that falls through to "give up, no data" — the safe default. Had I defined success as "not retryable," a 302 would be treated as a valid response and `getCustomerId()` would hand back null.

---

# `DefaultCard` — a record, unrolled

```java
private final String firstName;
private final String lastName;
private final String cardLastFour;
```
`cardLastFour` is a `String`, not an `int`. Card suffixes have leading zeros — `0042` as an int is `42`, which renders as `**42` and is wrong. Identifiers that merely look numeric should be strings; you never do arithmetic on them.

```java
static DefaultCard from(PaymentResponse r) {
    return new DefaultCard(r.getFirstName(), r.getLastName(), r.getCardLastFour());
}
```
Package-private, not public: it's an internal adapter from the wire type to the domain type, not part of the API. It's static rather than an instance method on `PaymentResponse` because the mapping direction should point *away* from the transport layer — `PaymentResponse` shouldn't know that a `DefaultCard` exists, or the two layers become mutually dependent.

Its real payoff is at the call site: `.map(DefaultCard::from)` is a method reference, which only works because the signature is exactly `PaymentResponse -> DefaultCard`.

```java
@Override public boolean equals(Object o) {
    if (this == o) return true;
```
Identity short-circuit. Free correctness for the common self-comparison, and it avoids three string comparisons.

```java
    if (!(o instanceof DefaultCard)) return false;
```
`instanceof` returns `false` for `null`, so this handles the null case in the same line — no separate `o == null` check needed. Java 16's pattern form (`o instanceof DefaultCard that`) would fold the next line in too, but that's unavailable on 15, hence the explicit cast below.

```java
    DefaultCard that = (DefaultCard) o;
    return Objects.equals(firstName, that.firstName) && ...
```
`Objects.equals`, not `firstName.equals(...)` — any of these fields can be null if a service returns a partial payload, and the raw call would NPE. `Objects.equals` handles null-on-either-side.

```java
@Override public int hashCode() { return Objects.hash(firstName, lastName, cardLastFour); }
```
Same fields as `equals`, in the same order. The contract is *equal objects must have equal hashcodes*; the only reliable way to keep that true is to derive both from an identical field list. Overriding `equals` without `hashCode` silently breaks every `HashMap` and `HashSet` these ever land in — a bug that shows up months later as "the cache never hits."

```java
@Override public String toString() {
    return "DefaultCard[" + firstName + " " + lastName + ", ****" + cardLastFour + "]";
}
```
The `****` prefix is deliberate. `toString()` output ends up in logs, and logs get shipped to systems with wider access than the database. Rendering it as visibly-masked means nobody reading a log ever mistakes it for a full PAN. This is also why only the last four are in the object at all — the full number never enters this process.

---

# `BootstrapResponse` — where the invariants get enforced

```java
private final DefaultCard defaultCard;   // nullable by contract
private final String address;            // never null; "" when unknown
```
Two different absence markers, and the comments exist because the asymmetry looks like sloppiness otherwise. A card is compound — `DefaultCard("","","")` is a lie that renders as a blank card. An address is a string that gets concatenated and rendered, where `""` is genuinely the right zero value and never NPEs.

```java
this.customerId = Objects.requireNonNull(customerId, "customerId");
```
This is the class's central invariant made executable: **a `BootstrapResponse` cannot exist without a customer id.** The named message means the NPE says `customerId` instead of leaving you to guess which of three arguments was null. Failing here — at construction — beats failing three layers away when a caller dereferences it.

```java
this.address = (address == null) ? "" : address;
```
Normalize at the boundary. The field's contract says "never null," so the constructor is the one place that guarantees it, and every reader downstream is freed from null checks. Note this *coerces* rather than rejecting: a null address is a legitimate degraded state, unlike a null customerId which is a broken one. The different treatment of the two arguments in adjacent lines is exactly the point.

```java
public DefaultCard getDefaultCard() { return defaultCard; }
```
Returns a possibly-null reference rather than `Optional<DefaultCard>`. Debatable — `Optional` as a return type would document the absence in the signature. I chose the null because the field is part of a serialized API response where `Optional` doesn't map cleanly to JSON, and because `Optional` fields carry a real cost in a type that may be created per request. In a pure internal domain model I'd use `Optional` here.

---

# `NamedThreadFactory`

```java
private final AtomicInteger counter = new AtomicInteger();
```
`AtomicInteger`, not `int`. `newThread` is called from whichever thread is submitting work, and a pool ramping up under load calls it concurrently. A plain `int++` is read-modify-write and would hand two threads the same name.

```java
Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
```
The whole reason this class exists. The default factory produces `pool-2-thread-7`, where the pool number is a JVM-wide counter that shifts as unrelated code initializes. `bootstrap-io-7` tells you immediately, in a thread dump at 3am, which pool is saturated — and with two pools whose distinction is load-bearing, that's not a nicety.

```java
t.setDaemon(true);
```
Non-daemon threads keep the JVM alive. If any caller forgets `close()`, a non-daemon pool means the process hangs at shutdown with no visible error. Daemon threads make the failure mode "JVM exits, maybe mid-request" rather than "JVM never exits" — the former is diagnosable, the latter gets misfiled as a deploy bug.

---

# `ResilientCaller.Policy`

```java
if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
```
`maxAttempts = 0` would make the loop body never execute, so `call()` returns empty without ever contacting the service. That's a silent total outage from one bad config value. Fail loudly at construction instead.

```java
public static Policy defaults() {
    return new Policy(3, Duration.ofSeconds(2), Duration.ofSeconds(5),
                      Duration.ofMillis(50), Duration.ofMillis(400));
}
```
A five-argument constructor of same-typed values is a call site nobody can read, and swapping two `Duration`s compiles fine. The named factory means the common case never touches the raw constructor. (A builder is the fuller answer; a static factory is the right weight for one common configuration.)

**3 attempts** — the second attempt catches most transient blips; a third helps marginally; beyond that you're mostly adding load to a service that's genuinely down, which is what circuit breakers are for.

**2s per attempt** is from the spec. **5s total budget** is the line that matters most:

```java
private final Duration perAttemptTimeout;
private final Duration totalBudget;
```
Two knobs, because one isn't enough. With only the per-attempt timeout, worst case is `3 × 2s + backoffs ≈ 6.5s`, and that number silently grows every time somebody bumps `maxAttempts` in a config file. The total budget is absolute: whatever the other four values are, this call returns within 5 seconds. **That's the number that goes in the SLA**, and it's derived from the policy rather than from arithmetic someone has to redo after every tuning change.

`Duration` rather than `long millis` throughout: `new Policy(3, 2000, 5000, 50, 400)` has no unit information at the call site and invites a seconds/millis mixup. `Duration.ofSeconds(2)` cannot be misread.

---

# `ResilientCaller.call` — line by line

```java
public <T extends HttpResponse> Optional<T> call(String op, Callable<T> call) {
```

**`<T extends HttpResponse>`** — the bound is what lets the body call `resp.isSuccess()`. Without it, `T` would be `Object` and the retry logic couldn't inspect the result. With it, one method serves all three services and returns the *precise* type, so the caller gets a `PaymentResponse`, not a cast.

**`Callable<T>`, not `Supplier<T>`** — `Callable.call()` throws `Exception`. A service that throws `IOException` can be passed as a lambda directly. `Supplier` would force every call site to wrap in try/catch just to satisfy the compiler.

**`Callable` and not `T` directly** — the parameter must be *re-invocable*, because this is the unit that runs up to three times. Passing a value would mean the call already happened and there'd be nothing to retry.

**`Optional<T>` return, and the method never throws** — the single most important design decision here. Every failure mode (5xx, 4xx, timeout, connection reset, rejected, interrupted, budget exhausted) converges on `Optional.empty()`. That's what collapses the aggregator's entire "graceful partial failure" requirement into `.map(...).orElse(default)` instead of nested try/catch.

**`String op`** — honest note: it's currently unused. It was carrying log/metric context (`log.warn("{} attempt {} failed", op, attempt)`) and I stripped the logging to keep the sample readable. In real code it feeds structured logs and a `retries_total{service="PaymentService"}` counter. As written, a reviewer would rightly flag it as a dead parameter — either wire up the logging or drop it.

```java
final long deadline = System.nanoTime() + policy.getTotalBudget().toNanos();
```
**`nanoTime`, never `currentTimeMillis`.** `nanoTime` is monotonic; `currentTimeMillis` tracks the wall clock and jumps when NTP corrects drift. A backwards jump mid-request makes `deadline - now` produce a wildly wrong remaining time — either an instant spurious timeout or a multi-second hang. For measuring *elapsed* time it's always `nanoTime`. (Its absolute value is meaningless — only differences are defined — which is fine, since every use here is a subtraction.)

Computed **once, before the loop.** The budget covers the whole retry sequence including backoffs; recomputing it per attempt would reset it each time and defeat the ceiling entirely.

```java
for (int attempt = 1; attempt <= policy.getMaxAttempts(); attempt++) {
```
1-based, so `attempt` reads as "attempt number" in logs and in the backoff exponent, where the first retry should shift by 0.

```java
long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
if (remainingMs <= 0) break;
```
Re-reads the clock **every iteration**. Time passed during the previous attempt and its backoff; a fixed timeout would let three attempts collectively overrun the budget. `break`, not `return`, so there's exactly one exit point for the failure path at the bottom of the method.

```java
long timeoutMs = Math.min(policy.getPerAttemptTimeout().toMillis(), remainingMs);
```
This one line is what makes the total budget a hard guarantee rather than an aspiration. If attempt 3 starts with 700ms left, it gets 700ms — not 2s. **The budget can never be overrun by construction.**

```java
Future<T> f;
try {
    f = io.submit(call);
} catch (RejectedExecutionException e) {
    return Optional.empty();
}
```
`submit` is in its **own** try block, separate from `get`. It has to be: the `catch` clauses below all reference `f`, which isn't assigned until `submit` returns. Merging them wouldn't compile.

`RejectedExecutionException` is unchecked, so nothing forces you to handle it — which is exactly why it's easy to miss and why it took the port from virtual threads to surface it. It fires when the bounded pool is saturated or shut down, and it propagates **synchronously** out of `submit`. Uncaught, it escapes `call()` and breaks the never-throws contract that the entire aggregator is built on.

**Returns immediately instead of retrying**, deliberately. A full thread pool isn't a transient blip you can wait out with 50ms of backoff — it means every thread is already blocked on this same downstream. Retrying adds pressure to the thing that's already failing. Fast load shedding is the correct response, and it's the one case where "give up now" beats "back off."

```java
T resp = f.get(timeoutMs, TimeUnit.MILLISECONDS);
```
**The timeout mechanism, in one line.** The blocking service call runs on an `io` thread; this thread waits with a bound. Worth being precise about what this does and doesn't do: it does not make the call faster, and it does not stop it. It guarantees *this* thread regains control in `timeoutMs`. The runaway call is still out there — which is why the next line exists.

```java
if (resp != null && resp.isSuccess()) return Optional.of(resp);
```
The only path that returns data. `Optional.of`, not `ofNullable` — the null guard already ran, so if `resp` were somehow null here I *want* the NPE, because it means my own invariant is broken. Using `ofNullable` would convert a logic bug into a silent empty result.

```java
if (resp != null && !resp.isRetryable()) return Optional.empty();
```
The 4xx path. Retrying an identical malformed request three times triples load on a call that will never succeed, and burns 4+ seconds of the caller's budget to arrive at the same answer. Returning immediately is both faster and kinder to the downstream. `!isRetryable()` rather than an explicit `4xx` check keeps the classification in the one place that owns it.

```java
// else: 5xx -> fall through to backoff
```
The comment marks a deliberate absence of code. A reader seeing two `if`s with no `else` needs to know the fall-through is intentional, not a missing branch.

```java
} catch (TimeoutException e) {
    f.cancel(true);
}
```
**`cancel(true)`** — `true` means *interrupt the running thread*. Without it, the abandoned attempt keeps running forever, holding a pool thread and an HTTP connection. Under sustained load with timeouts, that's a thread leak that eventually takes the whole service down. It doesn't only affect this request; it poisons the pool for everyone.

The honest limitation: interruption only unblocks a thread parked in something interruptible. A thread blocked in a raw socket read ignores the flag. The real fix is a connect/read timeout configured on the HTTP client itself; this is the outer safety net that guarantees *the caller* returns even when the orphan lingers. Both layers are needed — client timeouts alone can't cover a client that hangs in DNS or TLS, and this alone can't reclaim the thread.

No `return` here — a timeout is exactly the transient condition retries exist for.

```java
} catch (ExecutionException e) {
}
```
`ExecutionException` is the wrapper `Future.get` throws when the `Callable` itself threw — connection reset, DNS failure, JSON parse error. Semantically that's the same class of problem as a 5xx, so it takes the same path: fall through, back off, retry.

The empty body is the code smell that most deserves a comment (there is one in the source). In production this is where `e.getCause()` gets logged — swallowing exceptions with no record is how outages become unexplainable. The *control flow* is right; the observability is what's stubbed.

```java
} catch (InterruptedException e) {
    f.cancel(true);
    Thread.currentThread().interrupt();
    return Optional.empty();
}
```
Three lines, each mandatory.

Catching `InterruptedException` **clears the thread's interrupt flag as a side effect.** If you swallow it, the code above you on the stack never learns a shutdown was requested and keeps working — the classic reason a service takes 30 seconds to die and then gets `SIGKILL`ed mid-write. `Thread.currentThread().interrupt()` restores the flag so the caller can see it.

And it `return`s rather than retrying: someone asked us to stop. Retrying in the face of an interrupt is precisely the behavior the interrupt was trying to prevent.

```java
if (attempt < policy.getMaxAttempts()) backoff(attempt, deadline);
```
Without the guard you'd sleep up to 400ms after the *final* failed attempt and then return empty anyway — pure added latency on the path that's already the slowest, for zero benefit. Small line, easy to omit, directly visible in your p99.

```java
return Optional.empty();
```
Single failure exit. Reached by budget exhaustion or by exhausting attempts.

---

# `ResilientCaller.backoff`

```java
long exp = policy.getBaseBackoff().toMillis() << (attempt - 1);
```
`<< (attempt - 1)` is `× 2^(attempt-1)`: 50, 100, 200, 400. Each retry waits longer, giving a struggling service room to recover instead of piling on. The shift is used over `Math.pow` because it's integer arithmetic — no float→long rounding, no `pow` call in a path that runs on every failure.

Latent issue worth naming: long shifts in Java are taken mod 64, so a `maxAttempts` above ~64 would wrap the exponent and silently produce a *small* backoff. Harmless at 3 attempts, but it's the kind of thing that bites when someone makes the policy configurable. `Math.min` on a pre-clamped exponent, or capping the shift at 30, closes it.

```java
long capped = Math.min(exp, policy.getMaxBackoff().toMillis());
```
Stops the exponential becoming absurd. Growth is only useful up to roughly the service's recovery time; past that you're just adding latency to a request that will probably succeed.

```java
long jitter = ThreadLocalRandom.current().nextLong(capped + 1);
```
The subtle one, and the one interviewers probe. Picture a downstream that briefly 500s under a burst. **Without jitter, every client that failed at time T retries at exactly T+50ms, then T+150ms** — a synchronized thundering herd that re-kills the service the moment it comes back, in a self-sustaining cycle. Randomizing over `[0, capped]` smears retries across the window and breaks the lockstep.

This is *full* jitter (uniform over the whole range), not "exponential plus a bit of noise." It's the AWS-recommended variant and it measurably outperforms the timid version, because partial jitter leaves the peaks correlated.

`ThreadLocalRandom`, not `Random` or `Math.random()`: a shared `Random` has a single `AtomicLong` seed, so every thread CASes the same cache line on every call. Under exactly the concurrent-retry-storm conditions this code exists to handle, that's contention in the hot path.

`capped + 1` because `nextLong(bound)` is exclusive on the upper end; the `+1` makes the full backoff reachable. It also keeps the bound ≥ 1, since `nextLong(0)` throws.

```java
long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
long sleepMs = Math.min(jitter, left);
if (sleepMs <= 0) return;
```
**Even the sleep respects the budget.** Without this, a 400ms nap could push past the 5s ceiling, and the next loop iteration would exit immediately — you'd have slept purely to miss the deadline. The `<= 0` guard also covers `Thread.sleep(negative)`, which throws `IllegalArgumentException`.

```java
try { Thread.sleep(sleepMs); }
catch (InterruptedException e) { Thread.currentThread().interrupt(); }
```
Same flag-restoration discipline. It doesn't return early because the loop's next `remainingMs` check and the `get` call will see the interrupt and exit cleanly one step later — but the flag must survive to make that happen.

---

# `BootstrapAggregator`

```java
public final class BootstrapAggregator implements AutoCloseable {
```
`final`: this class owns thread pools with a specific interaction discipline. A subclass overriding `bootstrap` could easily break the pool-separation invariant that keeps it from deadlocking. `AutoCloseable` so callers get try-with-resources and can't leak pools. (Java 19 made `ExecutorService` itself `AutoCloseable`; on 15 you write it yourself.)

```java
private static final String DEFAULT_ADDRESS = "";
```
Named constant for an empty string, which looks like ceremony but isn't. `""` at a use site is indistinguishable from an accident. `DEFAULT_ADDRESS` says *this specific empty string is the documented fallback*, and it's the one place to change if the answer ever becomes `"UNKNOWN"`.

```java
private final ExecutorService fanOut;   // runs the two independent branches
private final ExecutorService io;       // runs the individual blocking attempts
```

**The single most important design decision in the file, and the one that changed when we dropped to Java 15.**

There is nested submission here: a `fanOut` task calls `caller.call(...)`, which submits to `io` and *blocks waiting on it*. Share one bounded pool between them and you get a textbook pool-induced deadlock — the two branch threads occupy every slot while the attempts they're waiting on sit queued behind them, unable to start. Nothing progresses until the timeouts fire, and then everything fails at once.

Under virtual threads this was a non-issue (neither pool was meaningfully bounded). With real pools it's a live hazard, which is why the comment in the source is phrased as a warning rather than a note. **General rule: a thread pool whose tasks block on tasks in the same pool is a deadlock waiting for load.**

```java
public BootstrapAggregator(UserService u, PaymentService p, AddressService a) {
    this(u, p, a, ResilientCaller.Policy.defaults(), 64, 64);
}
```
Constructor chaining: the convenience form supplies defaults and delegates, so there's exactly one place where fields get assigned. Tests use the full constructor to inject an aggressive policy (1 attempt, 50ms timeout) and tiny pools to exercise rejection paths.

```java
this.userService = Objects.requireNonNull(u);
```
Fail at construction, not at first request. A null service injected by a misconfigured DI container should blow up at startup where it's obvious, not produce a 500 an hour later under load.

```java
this.fanOut = newElasticPool("bootstrap-fanout", maxFanOutThreads);
this.io     = newElasticPool("bootstrap-io", maxIoThreads);
this.caller = new ResilientCaller(io, policy);
```
`caller` is constructed last because it needs `io`. Note `caller` is given **only** `io` — it structurally cannot submit to `fanOut`, so the layering that prevents the deadlock is enforced by what each object can reach, not by everyone remembering the rule.

```java
return new ThreadPoolExecutor(
        0, max,
        60L, TimeUnit.SECONDS,
        new SynchronousQueue<Runnable>(),
        new NamedThreadFactory(name),
        new ThreadPoolExecutor.AbortPolicy());
```

Every argument is a decision:

**`corePoolSize = 0`** — no threads held when idle. This is a request-driven service; a pool sitting on 64 threads overnight is 64 stacks of wasted memory.

**`SynchronousQueue`** — this is the load-bearing choice. A `SynchronousQueue` has no capacity; `offer` succeeds only if a thread is *already waiting to take*. Combined with `ThreadPoolExecutor`'s ordering (try core → try queue → try new thread → reject), the effect is **grow to `max`, then reject**.

Swap in the `LinkedBlockingQueue` that `newFixedThreadPool` uses and the behavior inverts: it queues instead of growing, unboundedly. For blocking I/O that means requests pile up invisibly, latency climbs without bound, and you eventually OOM — all while the pool reports itself healthy. Fast, visible rejection beats slow, invisible collapse.

**`60s` keep-alive** — matches `newCachedThreadPool`. Long enough to reuse threads across a burst, short enough to release them after.

**`AbortPolicy`, and specifically *not* `CallerRunsPolicy`** — this deserves its own paragraph, because `CallerRunsPolicy` is the usual right answer for backpressure and it is **wrong here.** It executes the rejected task inline on the calling thread. For the `io` pool that means `io.submit(call)` would run the entire HTTP call before returning, and the subsequent `f.get(timeoutMs)` would find an already-completed future. **The 2-second deadline would silently not be enforced** — a request could block for 30 seconds with the timeout code sitting right there, looking correct. Under saturation, precisely when you most need the deadline, it would evaporate.

```java
Optional<UserResponse> user = caller.call("UserService",
        () -> userService.getResponse(new UserRequest(userId)));
```
Sequential and unavoidable — a genuine data dependency, since `customerId` is the input to both downstream calls. This is the one place serialization is required rather than chosen.

```java
if (!user.isPresent() || user.get().getCustomerId() == null) {
    return Optional.empty();
}
```
Two distinct failure modes in one guard. The first is transport-level (`ResilientCaller` gave up). The second catches **200 with a null field** — a real thing that happens, and something no amount of status-code checking will catch. Without it, `customerId` propagates as null into the two downstream requests, which then 4xx, and you get a confusing three-service failure from one bad upstream response.

Returning `Optional.empty()` rather than a partial response is the spec's requirement made structural: no customer id means no downstream calls are even *formable*. The caller maps this to a 503.

```java
final String customerId = user.get().getCustomerId();
```
`final` because it's captured by both lambdas. It's effectively final already so the compiler wouldn't complain, but the explicit keyword documents *why* it must not be reassigned — capture by two other threads.

```java
CompletableFuture<Optional<PaymentResponse>> paymentF = submitBranch(
        () -> caller.call("PaymentService",
                () -> paymentService.getResponse(new PaymentRequest(customerId))));
```

Both futures start **immediately** on construction, so total wall time is `max(payment, address)` rather than `payment + address` — roughly 2s instead of 4s in the worst case, which is the requirement.

The doubly-nested lambda is worth unpacking, since it confuses people. The **outer** lambda is the branch, running on a `fanOut` thread; it includes the whole retry loop. The **inner** lambda is one attempt, handed to `ResilientCaller`, and it's the thing that may run three times. Two lambdas because they're two different units of work with different lifetimes.

```java
private <T> CompletableFuture<Optional<T>> submitBranch(Supplier<Optional<T>> work) {
    try {
        return CompletableFuture.supplyAsync(work, fanOut);
    } catch (RejectedExecutionException e) {
        return CompletableFuture.completedFuture(Optional.<T>empty());
    }
}
```
The second place bounded pools bite. `supplyAsync` calls `executor.execute(...)` **synchronously**, inside the call — so a rejection throws right here on the calling thread, not into the returned future. Uncaught, a saturated `fanOut` pool would blow up a bootstrap that could have degraded to `defaultCard = null` and still served the page.

Returning an already-completed empty future means the failure enters the **same** channel as every other failure, so `joinOrEmpty` and the `.orElse` defaults below need no special case. The alternative — a null future, or a boolean flag — would fork the downstream logic.

The `Optional.<T>empty()` type witness: inference would probably manage it from the return target, but being explicit costs nothing and makes generic code readable.

Also note this method exists at all rather than being inlined twice. Two copies of a subtle try/catch is two chances to fix only one of them later.

```java
DefaultCard card = joinOrEmpty(paymentF).map(DefaultCard::from).orElse(null);
String address   = joinOrEmpty(addressF).map(AddressResponse::getAddress).orElse(DEFAULT_ADDRESS);
```
**This is the payoff for every decision above.** The entire "handle partial failures gracefully" requirement is two lines, because `ResilientCaller` never throws and always returns `Optional`. Success maps through; 500s, timeouts, exhausted retries, connection resets, and pool rejections all arrive as `Optional.empty()` and fall to the documented default. There is no `if` and no `try` in the business logic — the error handling was pushed to the layer that owns it.

The ordering matters too: `paymentF` is joined first, but both branches are already running, so joining in sequence costs nothing. The wait is `max`, not sum.

```java
private static <T> Optional<T> joinOrEmpty(CompletableFuture<Optional<T>> f) {
    try { return f.join(); }
    catch (CompletionException | CancellationException e) { return Optional.empty(); }
}
```
`join()` over `get()` because `get()` throws checked `InterruptedException` and `ExecutionException`, which would put try/catch back into the business logic. `join` throws the unchecked `CompletionException` instead — hence catching that specific type.

Belt and braces: `caller.call` shouldn't throw. But if the lambda blows up in a way I didn't anticipate — an `Error`, a bug in my own mapping code — `join` rethrows it wrapped. Without this catch, one unexpected exception in the *payment* branch takes down a bootstrap that could have succeeded. The whole premise is that one flaky service can't blow up the request, and that guarantee shouldn't have a hole in it.

Multi-catch because both types get identical treatment; separate blocks would be duplicated code.

And critically: **these joins cannot hang.** Each branch is internally bounded by the 5s total budget, so there's no unbounded wait. A `join()` with no timeout is only safe when you can prove that — here I can, which is why `orTimeout` isn't needed on top.

```java
return Optional.of(new BootstrapResponse(customerId, card, address));
```

**Where the thread-safety requirement went.** The spec asks about locking the aggregated response; this design needs no lock, for two reasons:

1. The workers never touch shared state. Each computes a value and returns it through its own future. **There is no shared mutable object to race on.**
2. The response is constructed on the caller thread, *after* both joins, from local variables, and it's immutable.

`CompletableFuture.join()` also establishes a happens-before edge: everything a worker did before completing its future is guaranteed visible to the joining thread. No `volatile`, no lock, no `ConcurrentHashMap` — the memory model provides it.

The alternative design (workers writing into a shared mutable response under a lock) is strictly worse: more code, a contention point, and a whole family of visibility bugs, in exchange for nothing. **Structuring the concurrency so shared state doesn't exist beats synchronizing shared state.** That's the answer if the interviewer pushes.

```java
@Override public void close() {
    shutdown(fanOut);
    shutdown(io);
}
```
`fanOut` first, deliberately. Branch threads depend on `io` threads; killing `io` first would strand branches waiting on futures that can never complete. Shut down in reverse dependency order — same principle as resource release generally.

```java
private static void shutdown(ExecutorService es) {
    es.shutdown();
    try {
        if (!es.awaitTermination(5, TimeUnit.SECONDS)) es.shutdownNow();
```
The standard two-phase shutdown. `shutdown()` is graceful — it stops accepting new work and lets in-flight requests finish — but it **does not block**, which is why people think it isn't working. `awaitTermination` is the actual wait. If 5 seconds isn't enough, `shutdownNow()` interrupts the stragglers. Graceful first, forceful second: in-flight user requests get a chance to complete, but a wedged thread can't hold shutdown hostage forever.

```java
    } catch (InterruptedException e) {
        es.shutdownNow();
        Thread.currentThread().interrupt();
    }
```
If we're interrupted *while waiting for shutdown*, the process is going down harder than expected. Escalate immediately to `shutdownNow` and restore the flag. Third occurrence of the same discipline — it's the one piece of etiquette that has to be applied everywhere or it's worthless.

---

## What I'd flag in review of my own code

Three honest gaps, since a line-by-line reading surfaces them:

- **`String op` is unused** in `ResilientCaller.call`. Either wire up the logging/metrics it was meant to key, or delete the parameter.
- **The empty `catch (ExecutionException)` block** swallows the cause with no record. Correct control flow, missing observability — log `e.getCause()` at WARN.
- **`nextLong(bound)` and the `<<` exponent** assume sane policy values. Fine at `maxAttempts = 3`; worth hardening if the policy ever becomes externally configurable.

None of these change behavior at the current settings, but the first two are exactly what makes a production incident harder to diagnose than it needs to be.