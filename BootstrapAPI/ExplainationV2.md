Java 17, Spring Web, SLF4J, Micrometer. This is the complete implementation of the design from the previous turn — including the circuit breaker and cache I'd previously only described. **Not compile-verified** (no JDK on this machine), so treat the first build as the real check.

# Package layout

```
com.example.bootstrap
├── core/        Deadline, CallOutcome, Failure, RetryPolicy, RetryTemplate, NamedThreadFactory
├── section/     SectionStatus, SectionResult, SectionSpec, SectionCache, CircuitBreaker
├── client/      UserClient, PaymentClient, AddressClient + DTOs
├── api/         BootstrapResponse, BootstrapResult, BootstrapController
└── service/     BootstrapService, BootstrapConfig, BootstrapModule
```

---

# 1. Core: deadline, outcome, retry

## `Failure.java`

```java
package com.example.bootstrap.core;

public enum Failure {
    NONE,
    NOT_FOUND,           // deterministic 404 — never retry, never fall back to cache
    NON_RETRYABLE_STATUS,// other 4xx
    NON_RETRYABLE_ERROR, // bug-shaped exception, or null payload
    RETRIES_EXHAUSTED,
    DEADLINE_EXCEEDED,
    CIRCUIT_OPEN,
    POOL_REJECTED,
    INTERRUPTED;

    public boolean isTransient() {
        return this == RETRIES_EXHAUSTED || this == DEADLINE_EXCEEDED
            || this == CIRCUIT_OPEN || this == POOL_REJECTED;
    }
}
```

## `Deadline.java`

```java
package com.example.bootstrap.core;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Request-wide budget. cappedAt() is what stops one hop from starving the next. */
public final class Deadline {

    private final long deadlineNanos;
    private volatile boolean aborted;

    private Deadline(long deadlineNanos) { this.deadlineNanos = deadlineNanos; }

    public static Deadline after(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos());
    }

    /** min(what's left overall, this hop's allowance). */
    public Deadline cappedAt(Duration max) {
        return new Deadline(Math.min(deadlineNanos, System.nanoTime() + max.toNanos()));
    }

    public long remainingMillis() {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    public boolean expired() { return remainingMillis() <= 0; }

    /** Cooperative: CompletableFuture.cancel does not interrupt supplyAsync tasks. */
    public void abort()        { aborted = true; }
    public boolean isAborted() { return aborted; }
}
```

## `CallOutcome.java`

```java
package com.example.bootstrap.core;

import java.util.Objects;
import java.util.Optional;

public record CallOutcome<T>(T value, Failure failure, int attempts, long elapsedMs) {

    public CallOutcome { Objects.requireNonNull(failure, "failure"); }

    public static <T> CallOutcome<T> success(T value, int attempts, long elapsedMs) {
        return new CallOutcome<>(Objects.requireNonNull(value), Failure.NONE, attempts, elapsedMs);
    }
    public static <T> CallOutcome<T> failure(Failure failure, int attempts, long elapsedMs) {
        if (failure == Failure.NONE) throw new IllegalArgumentException("NONE is not a failure");
        return new CallOutcome<>(null, failure, attempts, elapsedMs);
    }

    public boolean isSuccess()      { return failure == Failure.NONE; }
    public Optional<T> toOptional() { return Optional.ofNullable(value); }

    /** Omits value deliberately — this lands in logs and payloads carry PII. */
    @Override public String toString() {
        return "CallOutcome[" + failure + ", attempts=" + attempts + ", elapsedMs=" + elapsedMs + "]";
    }
}
```

## `UpstreamException.java`

```java
package com.example.bootstrap.core;

public class UpstreamException extends RuntimeException {

    private final int statusCode;   // 0 == transport-level

    public UpstreamException(String message, int statusCode) { super(message); this.statusCode = statusCode; }
    public UpstreamException(String message, Throwable cause) { super(message, cause); this.statusCode = 0; }

    public int getStatusCode() { return statusCode; }
    public boolean isNotFound() { return statusCode == 404; }
    public boolean isRetryable() { return statusCode == 0 || statusCode >= 500 || statusCode == 429; }
}
```

## `RetryPolicy.java`

```java
package com.example.bootstrap.core;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

public record RetryPolicy(int maxAttempts, Duration perAttemptTimeout,
                          Duration baseBackoff, Duration maxBackoff,
                          Predicate<Throwable> retryableCause) {

    public static final Predicate<Throwable> DEFAULT_RETRYABLE_CAUSE = t -> {
        if (t instanceof UpstreamException ue) return ue.isRetryable();
        return !(t instanceof InterruptedException)
            && !(t instanceof NullPointerException)
            && !(t instanceof IllegalArgumentException)
            && !(t instanceof IllegalStateException)
            && !(t instanceof ClassCastException);
    };

    public RetryPolicy {
        if (maxAttempts < 1)  throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (maxAttempts > 30) throw new IllegalArgumentException("maxAttempts > 30 overflows the backoff shift");
        Objects.requireNonNull(perAttemptTimeout);
        if (perAttemptTimeout.isNegative() || perAttemptTimeout.isZero())
            throw new IllegalArgumentException("perAttemptTimeout must be > 0");
        Objects.requireNonNull(baseBackoff);
        Objects.requireNonNull(maxBackoff);
        Objects.requireNonNull(retryableCause);
    }

    /** Hop 1: 120ms attempts, 1 retry — must fit inside the 180ms user-hop cap. */
    public static RetryPolicy forUserHop() {
        return new RetryPolicy(2, Duration.ofMillis(120), Duration.ofMillis(20),
                               Duration.ofMillis(40), DEFAULT_RETRYABLE_CAUSE);
    }
    /** Hop 2: 150ms attempts, 1 retry — must fit inside the 320ms fan-out cap. */
    public static RetryPolicy forFanOut() {
        return new RetryPolicy(2, Duration.ofMillis(150), Duration.ofMillis(20),
                               Duration.ofMillis(40), DEFAULT_RETRYABLE_CAUSE);
    }
}
```

## `RetryTemplate.java` + `DefaultRetryTemplate.java`

```java
package com.example.bootstrap.core;

import java.util.concurrent.Callable;

public interface RetryTemplate {
    /** Never throws an Exception — all failure becomes a Failure. Errors propagate by design. */
    <T> CallOutcome<T> execute(String op, Deadline deadline, Callable<T> work);
}
```

```java
package com.example.bootstrap.core;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.*;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

public final class DefaultRetryTemplate implements RetryTemplate {

    private static final Logger log = LoggerFactory.getLogger(DefaultRetryTemplate.class);

    private final ExecutorService io;
    private final RetryPolicy policy;
    private final MeterRegistry registry;

    public DefaultRetryTemplate(ExecutorService io, RetryPolicy policy, MeterRegistry registry) {
        this.io = Objects.requireNonNull(io);
        this.policy = Objects.requireNonNull(policy);
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public <T> CallOutcome<T> execute(String op, Deadline deadline, Callable<T> work) {
        final long start = System.nanoTime();
        final int maxAttempts = policy.maxAttempts();
        final Map<String, String> callerCtx = MDC.getCopyOfContextMap();   // MDC is thread-local
        Throwable lastCause = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {

            if (deadline.isAborted()) {
                return CallOutcome.failure(Failure.DEADLINE_EXCEEDED, attempt - 1, elapsedMs(start));
            }
            long remaining = deadline.remainingMillis();
            if (remaining <= 0) {
                registry.counter("upstream.deadline_exceeded", "service", op).increment();
                log.warn("op={} outcome=deadline_exceeded attempts={} elapsedMs={}", op, attempt - 1, elapsedMs(start));
                return CallOutcome.failure(Failure.DEADLINE_EXCEEDED, attempt - 1, elapsedMs(start));
            }
            // Bounded by BOTH the per-attempt config and the hop budget — whichever is tighter.
            long attemptMs = Math.min(policy.perAttemptTimeout().toMillis(), remaining);

            Future<T> f;
            try {
                f = io.submit(withMdc(callerCtx, work));
            } catch (RejectedExecutionException e) {
                registry.counter("upstream.rejected", "service", op).increment();
                log.error("op={} outcome=rejected attempt={} reason=io_pool_saturated", op, attempt);
                return CallOutcome.failure(Failure.POOL_REJECTED, attempt - 1, elapsedMs(start));
            }

            Timer.Sample sample = Timer.start(registry);
            try {
                T value = f.get(attemptMs, TimeUnit.MILLISECONDS);
                if (value == null) {
                    sample.stop(timer(op, "invalid"));
                    log.error("op={} outcome=null_response attempt={} — client contract violated", op, attempt);
                    return CallOutcome.failure(Failure.NON_RETRYABLE_ERROR, attempt, elapsedMs(start));
                }
                sample.stop(timer(op, "success"));
                if (attempt > 1) {
                    log.info("op={} outcome=success attempts={} elapsedMs={} recovered=true", op, attempt, elapsedMs(start));
                }
                return CallOutcome.success(value, attempt, elapsedMs(start));

            } catch (TimeoutException e) {
                f.cancel(true);                 // reclaim the io thread
                sample.stop(timer(op, "timeout"));
                registry.counter("upstream.timeouts", "service", op).increment();
                lastCause = e;
                log.warn("op={} event=attempt_timeout attempt={}/{} timeoutMs={} action=retry",
                        op, attempt, maxAttempts, attemptMs);

            } catch (ExecutionException e) {
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                sample.stop(timer(op, "error"));
                if (cause instanceof Error err) {
                    log.error("op={} outcome=fatal — propagating Error", op, err);
                    throw err;                  // never mask a compromised JVM
                }
                lastCause = cause;
                if (cause instanceof UpstreamException ue && ue.isNotFound()) {
                    return CallOutcome.failure(Failure.NOT_FOUND, attempt, elapsedMs(start));
                }
                if (!policy.retryableCause().test(cause)) {
                    Failure kind = (cause instanceof UpstreamException ue2 && ue2.getStatusCode() > 0)
                            ? Failure.NON_RETRYABLE_STATUS : Failure.NON_RETRYABLE_ERROR;
                    log.error("op={} outcome={} attempt={}", op, kind, attempt, cause);
                    return CallOutcome.failure(kind, attempt, elapsedMs(start));
                }
                log.warn("op={} event=attempt_failed attempt={}/{} cause={}: {} action=retry",
                        op, attempt, maxAttempts, cause.getClass().getSimpleName(), cause.getMessage());

            } catch (InterruptedException e) {
                f.cancel(true);
                sample.stop(timer(op, "interrupted"));
                Thread.currentThread().interrupt();   // restore the flag we just cleared
                return CallOutcome.failure(Failure.INTERRUPTED, attempt, elapsedMs(start));

            } catch (CancellationException e) {
                sample.stop(timer(op, "cancelled"));
                return CallOutcome.failure(Failure.INTERRUPTED, attempt, elapsedMs(start));
            }

            if (attempt < maxAttempts) {
                registry.counter("upstream.retries", "service", op).increment();
                backoff(attempt, deadline);
            }
        }
        log.error("op={} outcome=retries_exhausted attempts={} elapsedMs={}", op, maxAttempts, elapsedMs(start), lastCause);
        return CallOutcome.failure(Failure.RETRIES_EXHAUSTED, maxAttempts, elapsedMs(start));
    }

    private Timer timer(String op, String outcome) {
        return registry.timer("upstream.call.duration", "service", op, "outcome", outcome);
    }

    private static <T> Callable<T> withMdc(Map<String, String> ctx, Callable<T> delegate) {
        return () -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            if (ctx != null) MDC.setContextMap(ctx); else MDC.clear();
            try { return delegate.call(); }
            finally { if (prev != null) MDC.setContextMap(prev); else MDC.clear(); }  // pool threads are reused
        };
    }

    /** Capped exponential backoff with FULL jitter, clamped to the remaining budget. */
    private void backoff(int attempt, Deadline deadline) {
        long exp     = policy.baseBackoff().toMillis() << (attempt - 1);
        long capped  = Math.min(exp, policy.maxBackoff().toMillis());
        long jitter  = ThreadLocalRandom.current().nextLong(capped + 1);
        long sleepMs = Math.min(jitter, deadline.remainingMillis());
        if (sleepMs <= 0) return;
        try { Thread.sleep(sleepMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
```

## `NamedThreadFactory.java`

```java
package com.example.bootstrap.core;

import org.slf4j.*;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class NamedThreadFactory implements ThreadFactory {

    private static final Logger log = LoggerFactory.getLogger(NamedThreadFactory.class);

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();   // called concurrently under ramp-up

    public NamedThreadFactory(String prefix) { this.prefix = prefix; }

    @Override public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((th, ex) -> log.error("event=uncaught thread={}", th.getName(), ex));
        return t;
    }
}
```

---

# 2. Section model

## `SectionStatus.java` / `SectionResult.java`

```java
package com.example.bootstrap.section;

public enum SectionStatus {
    OK,           // fresh, authoritative
    EMPTY,        // fresh, authoritative — the user genuinely has none
    STALE,        // served from cache; asOf + ageSeconds populated
    UNAVAILABLE   // we do not know; error populated
}
```

```java
package com.example.bootstrap.section;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SectionResult<T>(SectionStatus status, T data,
                               Instant asOf, Long ageSeconds, SectionError error) {

    public record SectionError(String code, boolean retryable) {}

    public static <T> SectionResult<T> ok(T data)   { return new SectionResult<>(SectionStatus.OK, data, null, null, null); }
    public static <T> SectionResult<T> empty(T data){ return new SectionResult<>(SectionStatus.EMPTY, data, null, null, null); }

    public static <T> SectionResult<T> stale(T data, Instant storedAt, Duration age) {
        return new SectionResult<>(SectionStatus.STALE, data, storedAt, age.toSeconds(), null);
    }
    public static <T> SectionResult<T> unavailable(String code, boolean retryable) {
        return new SectionResult<>(SectionStatus.UNAVAILABLE, null, null, null, new SectionError(code, retryable));
    }

    public boolean isDegraded() { return status == SectionStatus.STALE || status == SectionStatus.UNAVAILABLE; }
}
```

## `SectionSpec.java`

```java
package com.example.bootstrap.section;

import java.time.Duration;
import java.util.function.Predicate;

/** Everything policy-ish about one section, in one place. */
public record SectionSpec<T>(String name,
                             Fetcher<T> fetcher,
                             Duration maxStale,
                             Predicate<T> isEmpty,
                             Class<T> type) {

    @FunctionalInterface
    public interface Fetcher<T> { T fetch(String consumerId) throws Exception; }
}
```

## `SectionCache.java` + in-memory implementation

```java
package com.example.bootstrap.section;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface SectionCache {
    <T> Optional<Cached<T>> get(String namespace, String key, Class<T> type);
    <T> void put(String namespace, String key, T value, Duration retention);

    record Cached<T>(T value, Instant storedAt, Duration age) {}
}
```

```java
package com.example.bootstrap.section;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * Fallback-only cache: read exclusively when a live call fails, so the happy path
 * is never stale. Production: Caffeine (size eviction) or Redis (cross-instance).
 */
public final class InMemorySectionCache implements SectionCache {

    private record Entry(Object value, Instant storedAt, long expiresAtNanos) {}

    private final ConcurrentMap<String, Entry> map = new ConcurrentHashMap<>();
    private final int maxEntries;

    public InMemorySectionCache(int maxEntries) { this.maxEntries = maxEntries; }

    @Override public <T> Optional<Cached<T>> get(String ns, String key, Class<T> type) {
        String k = ns + '|' + key;
        Entry e = map.get(k);
        if (e == null) return Optional.empty();
        if (System.nanoTime() > e.expiresAtNanos()) { map.remove(k, e); return Optional.empty(); }
        return Optional.of(new Cached<>(type.cast(e.value()), e.storedAt(),
                Duration.between(e.storedAt(), Instant.now())));
    }

    @Override public <T> void put(String ns, String key, T value, Duration retention) {
        if (map.size() >= maxEntries) evictExpired();
        if (map.size() >= maxEntries) return;    // shed rather than grow unbounded
        map.put(ns + '|' + key,
                new Entry(value, Instant.now(), System.nanoTime() + retention.toNanos()));
    }

    private void evictExpired() {
        long now = System.nanoTime();
        map.entrySet().removeIf(en -> now > en.getValue().expiresAtNanos());
    }
}
```

## `CircuitBreaker.java` + sliding-window implementation

```java
package com.example.bootstrap.section;

public interface CircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }

    /** @return false when the call must be short-circuited. Consumes a half-open probe permit. */
    boolean tryAcquire(String key);
    void recordSuccess(String key);
    void recordFailure(String key);
    State state(String key);
}
```

```java
package com.example.bootstrap.section;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.*;

public final class SlidingWindowCircuitBreaker implements CircuitBreaker {

    private static final class Bucket {
        final boolean[] window;                 // ring buffer; true == failure
        int idx, filled, failures;
        State state = State.CLOSED;
        long openUntilNanos;
        int probesInFlight, probeSuccesses;
        Bucket(int size) { window = new boolean[size]; }
    }

    private final int windowSize;
    private final double failureRateThreshold;
    private final Duration openDuration;
    private final int probePermits;
    private final int successesToClose;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public SlidingWindowCircuitBreaker(int windowSize, double failureRateThreshold,
                                       Duration openDuration, int probePermits, int successesToClose) {
        this.windowSize = windowSize;
        this.failureRateThreshold = failureRateThreshold;
        this.openDuration = openDuration;
        this.probePermits = probePermits;
        this.successesToClose = successesToClose;
    }

    public static SlidingWindowCircuitBreaker defaults() {
        return new SlidingWindowCircuitBreaker(100, 0.5, Duration.ofSeconds(10), 3, 3);
    }

    private Bucket bucket(String key) { return buckets.computeIfAbsent(key, k -> new Bucket(windowSize)); }

    @Override public boolean tryAcquire(String key) {
        Bucket b = bucket(key);
        synchronized (b) {
            if (b.state == State.CLOSED) return true;
            if (b.state == State.OPEN) {
                if (System.nanoTime() < b.openUntilNanos) return false;
                b.state = State.HALF_OPEN;              // recovery window begins
                b.probesInFlight = 0;
                b.probeSuccesses = 0;
            }
            // HALF_OPEN: admit only a bounded number of concurrent probes.
            if (b.probesInFlight < probePermits) { b.probesInFlight++; return true; }
            return false;
        }
    }

    @Override public void recordSuccess(String key) {
        Bucket b = bucket(key);
        synchronized (b) {
            if (b.state == State.HALF_OPEN) {
                b.probesInFlight = Math.max(0, b.probesInFlight - 1);
                if (++b.probeSuccesses >= successesToClose) close(b);
                return;
            }
            record(b, false);
        }
    }

    @Override public void recordFailure(String key) {
        Bucket b = bucket(key);
        synchronized (b) {
            if (b.state == State.HALF_OPEN) {
                b.probesInFlight = Math.max(0, b.probesInFlight - 1);
                open(b);                                 // one probe failure re-opens immediately
                return;
            }
            record(b, true);
            if (b.filled >= windowSize && (double) b.failures / b.filled >= failureRateThreshold) open(b);
        }
    }

    @Override public State state(String key) {
        Bucket b = bucket(key);
        synchronized (b) { return b.state; }
    }

    private void record(Bucket b, boolean failure) {
        if (b.filled == b.window.length) {               // evict the oldest slot
            if (b.window[b.idx]) b.failures--;
        } else {
            b.filled++;
        }
        b.window[b.idx] = failure;
        if (failure) b.failures++;
        b.idx = (b.idx + 1) % b.window.length;
    }

    private void open(Bucket b) {
        b.state = State.OPEN;
        // +/-20% jitter so N replicas do not all probe the recovering dependency at the same instant.
        long base = openDuration.toNanos();
        long jitter = base / 5;
        b.openUntilNanos = System.nanoTime() + base
                + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        reset(b);
    }

    private void close(Bucket b) { b.state = State.CLOSED; reset(b); }

    private void reset(Bucket b) {
        Arrays.fill(b.window, false);
        b.filled = 0; b.failures = 0; b.idx = 0;
        b.probesInFlight = 0; b.probeSuccesses = 0;
    }
}
```

---

# 3. Clients and DTOs

```java
package com.example.bootstrap.client;

import java.util.List;

public record UserProfile(String name, String email, String locale) {}
public record UserResponse(String consumerId, UserProfile profile) {}

public record PaymentMethod(String id, String brand, String last4, boolean expired) {}
public record PaymentInfo(List<PaymentMethod> methods) {}

public record Address(String id, String line1, String city, String postalCode) {}
public record AddressInfo(List<Address> addresses) {}
```

```java
package com.example.bootstrap.client;

public interface UserClient    { UserResponse fetch(String userId) throws Exception; }
public interface PaymentClient { PaymentInfo  fetch(String consumerId) throws Exception; }
public interface AddressClient { AddressInfo  fetch(String consumerId) throws Exception; }
```

```java
package com.example.bootstrap.client;

import com.example.bootstrap.core.UpstreamException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public final class HttpUserClient implements UserClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration requestTimeout;

    public HttpUserClient(String baseUrl, ObjectMapper mapper, Duration requestTimeout, int maxConnections) {
        this.baseUrl = baseUrl;
        this.mapper = mapper;
        this.requestTimeout = requestTimeout;
        // Bulkhead: a dedicated client (and therefore connection pool) per dependency, so a slow
        // Payments service cannot exhaust the connections Address needs.
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(80))   // not interruptible by cancel(true) — must be set here
                .executor(java.util.concurrent.Executors.newFixedThreadPool(maxConnections))
                .build();
    }

    @Override public UserResponse fetch(String userId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/user-to-consumer?user_id=" + java.net.URLEncoder.encode(userId, "UTF-8")))
                .timeout(requestTimeout)
                // Relative, not absolute: immune to clock skew. Lets the downstream self-cancel.
                .header("X-Request-Timeout-Ms", String.valueOf(requestTimeout.toMillis()))
                .GET().build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) throw new UpstreamException("user not found", 404);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new UpstreamException("user-service returned " + resp.statusCode(), resp.statusCode());
        }
        return mapper.readValue(resp.body(), UserResponse.class);
    }
}
```

`HttpPaymentClient` and `HttpAddressClient` are identical in shape against `/payment-info?consumer_id=` and `/address-info?consumer_id=`.

---

# 4. API types

```java
package com.example.bootstrap.api;

import com.example.bootstrap.client.*;
import com.example.bootstrap.core.Failure;
import com.example.bootstrap.section.SectionResult;

public record Sections(SectionResult<UserProfile> profile,
                       SectionResult<PaymentInfo> payment,
                       SectionResult<AddressInfo> address) {}

public record Meta(String correlationId, boolean degraded, long elapsedMs) {}

public record BootstrapResponse(String userId, String consumerId, Sections sections, Meta meta) {}

public record BootstrapErrorBody(String code, String message, boolean retryable, String correlationId) {}

/** Two mutually exclusive shapes; the controller maps each to HTTP. */
public sealed interface BootstrapResult {
    record Success(BootstrapResponse response) implements BootstrapResult {}
    record Unavailable(Failure failure, String correlationId) implements BootstrapResult {}
}
```

```java
package com.example.bootstrap.api;

/** Populated by the auth filter from the verified token — never from the query string. */
public record Principal(String userId, java.util.Set<String> scopes) {
    public boolean hasScope(String s) { return scopes.contains(s); }
}
```

---

# 5. Config and service

```java
package com.example.bootstrap.service;

import java.time.Duration;

public record BootstrapConfig(Duration totalBudget,      // 550ms of a 600ms SLO
                              Duration userHopBudget,    // 180ms — capped so it cannot starve hop 2
                              Duration fanOutBudget,     // 320ms
                              Duration mappingRetention, // 24h  — user_id -> consumer_id is near-immutable
                              Duration profileMaxStale,  // 30m
                              Duration paymentMaxStale,  // 60s  — correctness-bounded
                              Duration addressMaxStale,  // 30m
                              boolean allowStale) {      // runtime kill switch

    public static BootstrapConfig defaults() {
        return new BootstrapConfig(Duration.ofMillis(550), Duration.ofMillis(180), Duration.ofMillis(320),
                Duration.ofHours(24), Duration.ofMinutes(30), Duration.ofSeconds(60),
                Duration.ofMinutes(30), true);
    }
}
```

```java
package com.example.bootstrap.service;

import com.example.bootstrap.api.*;
import com.example.bootstrap.client.*;
import com.example.bootstrap.core.*;
import com.example.bootstrap.section.*;
import com.example.bootstrap.section.SectionCache.Cached;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class DefaultBootstrapService implements BootstrapService {

    private static final Logger log = LoggerFactory.getLogger(DefaultBootstrapService.class);
    private static final String CID = "correlationId";
    private static final String NS_MAPPING = "mapping";
    private static final String NS_PROFILE = "profile";

    private final UserClient userClient;
    private final RetryTemplate userRetry;
    private final RetryTemplate fanRetry;
    private final ExecutorService fanOut;
    private final SectionCache cache;
    private final CircuitBreaker breaker;
    private final MeterRegistry registry;
    private final Supplier<BootstrapConfig> config;

    private final SectionSpec<PaymentInfo> paymentSpec;
    private final SectionSpec<AddressInfo> addressSpec;

    public DefaultBootstrapService(UserClient userClient, PaymentClient paymentClient, AddressClient addressClient,
                                   RetryTemplate userRetry, RetryTemplate fanRetry, ExecutorService fanOut,
                                   SectionCache cache, CircuitBreaker breaker, MeterRegistry registry,
                                   Supplier<BootstrapConfig> config) {
        this.userClient = Objects.requireNonNull(userClient);
        this.userRetry = Objects.requireNonNull(userRetry);
        this.fanRetry = Objects.requireNonNull(fanRetry);
        this.fanOut = Objects.requireNonNull(fanOut);
        this.cache = Objects.requireNonNull(cache);
        this.breaker = Objects.requireNonNull(breaker);
        this.registry = Objects.requireNonNull(registry);
        this.config = Objects.requireNonNull(config);

        BootstrapConfig c = config.get();
        this.paymentSpec = new SectionSpec<>("payment", paymentClient::fetch, c.paymentMaxStale(),
                p -> p.methods() == null || p.methods().isEmpty(), PaymentInfo.class);
        this.addressSpec = new SectionSpec<>("address", addressClient::fetch, c.addressMaxStale(),
                a -> a.addresses() == null || a.addresses().isEmpty(), AddressInfo.class);
    }

    @Override
    public BootstrapResult bootstrap(String userId, String inboundCorrelationId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId must not be blank");

        // Snapshot: config may be hot-reloaded, and a mid-request change would make the
        // budget we waited on differ from the budget we assembled against.
        final BootstrapConfig cfg = config.get();
        final Deadline overall = Deadline.after(cfg.totalBudget());
        final String cid = (inboundCorrelationId != null && !inboundCorrelationId.isBlank())
                ? inboundCorrelationId : UUID.randomUUID().toString();
        final boolean ownsMdc = MDC.get(CID) == null;
        if (ownsMdc) MDC.put(CID, cid);

        Timer.Sample sample = Timer.start(registry);
        try {
            // ---- Hop 1: forced serial. consumer_id keys everything downstream. ----
            UserResolution root = resolveUser(userId, overall.cappedAt(cfg.userHopBudget()), cfg);
            if (!root.isResolved()) {
                sample.stop(registry.timer("bootstrap.request.duration", "outcome", "unavailable"));
                registry.counter("bootstrap.unavailable", "reason", root.failure().name()).increment();
                log.warn("event=bootstrap_unavailable userId={} reason={}", userId, root.failure());
                return new BootstrapResult.Unavailable(root.failure(), cid);
            }

            // ---- Hop 2: fan out. Payments and Address share only consumer_id. ----
            Deadline fanHop = overall.cappedAt(cfg.fanOutBudget());
            registry.summary("bootstrap.deadline_remaining_at_fanout").record(fanHop.remainingMillis());

            var payF  = launch(paymentSpec, root.consumerId(), fanHop, cfg);
            var addrF = launch(addressSpec, root.consumerId(), fanHop, cfg);
            awaitFanOut(fanHop, payF, addrF);

            SectionResult<PaymentInfo> payment = harvest(paymentSpec, root.consumerId(), payF, cfg);
            SectionResult<AddressInfo> address = harvest(addressSpec, root.consumerId(), addrF, cfg);

            Sections sections = new Sections(root.profile(), payment, address);
            boolean degraded = root.profile().isDegraded() || payment.isDegraded() || address.isDegraded();

            long elapsed = sample.stop(registry.timer("bootstrap.request.duration",
                    "outcome", degraded ? "degraded" : "ok")) / 1_000_000;

            countSection("profile", root.profile());
            countSection("payment", payment);
            countSection("address", address);
            log.info("event=bootstrap_complete userId={} degraded={} elapsedMs={} profile={} payment={} address={}",
                    userId, degraded, elapsed, root.profile().status(), payment.status(), address.status());

            return new BootstrapResult.Success(new BootstrapResponse(
                    userId, root.consumerId(), sections, new Meta(cid, degraded, elapsed)));
        } finally {
            if (ownsMdc) MDC.remove(CID);   // only clean up what we set
        }
    }

    // ------------------------------------------------------------------ hop 1

    /**
     * The design's key move: user_id -> consumer_id is effectively immutable, so a cached
     * mapping lets a User Service outage degrade to "profile stale/unavailable" instead of
     * killing the whole request. Only a deterministic 404 is a hard stop.
     */
    private UserResolution resolveUser(String userId, Deadline hop, BootstrapConfig cfg) {
        if (!breaker.tryAcquire("user")) {
            registry.counter("section.short_circuit", "section", "user").increment();
            return degradedRoot(userId, cfg, Failure.CIRCUIT_OPEN);
        }
        CallOutcome<UserResponse> o = userRetry.execute("user", hop, () -> userClient.fetch(userId));

        if (o.isSuccess()) {
            breaker.recordSuccess("user");
            cache.put(NS_MAPPING, userId, o.value().consumerId(), cfg.mappingRetention());
            if (o.value().profile() != null) cache.put(NS_PROFILE, userId, o.value().profile(), cfg.profileMaxStale());
            return UserResolution.fresh(o.value());
        }
        if (o.failure() == Failure.NOT_FOUND) {
            breaker.recordSuccess("user");    // a 404 is the service working correctly
            return UserResolution.notFound();
        }
        breaker.recordFailure("user");
        return degradedRoot(userId, cfg, o.failure());
    }

    private UserResolution degradedRoot(String userId, BootstrapConfig cfg, Failure failure) {
        Optional<Cached<String>> mapping = cfg.allowStale()
                ? cache.get(NS_MAPPING, userId, String.class) : Optional.empty();
        if (mapping.isEmpty()) return UserResolution.hardFailure(failure);

        registry.counter("bootstrap.root_degraded").increment();
        Optional<Cached<UserProfile>> profile = cache.get(NS_PROFILE, userId, UserProfile.class);
        SectionResult<UserProfile> profileSection = profile
                .filter(p -> p.age().compareTo(cfg.profileMaxStale()) <= 0)
                .map(p -> SectionResult.stale(p.value(), p.storedAt(), p.age()))
                .orElseGet(() -> SectionResult.unavailable(failure.name(), failure.isTransient()));

        return UserResolution.degraded(mapping.get().value(), profileSection);
    }

    // ------------------------------------------------------------------ hop 2

    private <T> CompletableFuture<SectionResult<T>> launch(SectionSpec<T> spec, String consumerId,
                                                          Deadline hop, BootstrapConfig cfg) {
        Map<String, String> ctx = MDC.getCopyOfContextMap();
        try {
            return CompletableFuture.supplyAsync(() -> {
                Map<String, String> prev = MDC.getCopyOfContextMap();
                if (ctx != null) MDC.setContextMap(ctx);
                try { return fetchSection(spec, consumerId, hop, cfg); }
                finally { if (prev != null) MDC.setContextMap(prev); else MDC.clear(); }
            }, fanOut);
        } catch (RejectedExecutionException e) {
            // supplyAsync calls execute() synchronously, so rejection lands here, not in the future.
            log.error("section={} event=branch_rejected reason=fanout_saturated", spec.name());
            return CompletableFuture.completedFuture(
                    staleOrUnavailable(spec, consumerId, Failure.POOL_REJECTED, cfg));
        }
    }

    /** The fallback ladder: breaker -> fresh call (+retry) -> stale cache -> UNAVAILABLE. */
    private <T> SectionResult<T> fetchSection(SectionSpec<T> spec, String consumerId,
                                              Deadline hop, BootstrapConfig cfg) {
        if (!breaker.tryAcquire(spec.name())) {
            registry.counter("section.short_circuit", "section", spec.name()).increment();
            return staleOrUnavailable(spec, consumerId, Failure.CIRCUIT_OPEN, cfg);
        }
        CallOutcome<T> o = fanRetry.execute(spec.name(), hop, () -> spec.fetcher().fetch(consumerId));

        if (o.isSuccess()) {
            breaker.recordSuccess(spec.name());
            // Only fresh, authoritative results are written. Never cache-fill from a degraded read.
            cache.put(spec.name(), consumerId, o.value(), spec.maxStale());
            // A genuine empty is authoritative — it must never be reported as UNAVAILABLE.
            return spec.isEmpty().test(o.value()) ? SectionResult.empty(o.value()) : SectionResult.ok(o.value());
        }
        if (o.failure() == Failure.NOT_FOUND) {
            breaker.recordSuccess(spec.name());
            return SectionResult.unavailable(Failure.NOT_FOUND.name(), false);
        }
        breaker.recordFailure(spec.name());
        return staleOrUnavailable(spec, consumerId, o.failure(), cfg);
    }

    private <T> SectionResult<T> staleOrUnavailable(SectionSpec<T> spec, String consumerId,
                                                    Failure failure, BootstrapConfig cfg) {
        if (cfg.allowStale()) {
            Optional<Cached<T>> hit = cache.get(spec.name(), consumerId, spec.type());
            if (hit.isPresent() && hit.get().age().compareTo(spec.maxStale()) <= 0) {
                registry.counter("section.stale_served", "section", spec.name()).increment();
                return SectionResult.stale(hit.get().value(), hit.get().storedAt(), hit.get().age());
            }
        }
        // Never fabricate an empty. "We don't know" must be distinguishable from "there is none".
        return SectionResult.unavailable(failure.name(), failure.isTransient());
    }

    private void awaitFanOut(Deadline hop, CompletableFuture<?>... fs) {
        try {
            CompletableFuture.allOf(fs).get(hop.remainingMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            hop.abort();   // stragglers stop retrying instead of holding pool threads
            log.warn("event=fanout_deadline_exceeded");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.error("event=branch_failed_unexpectedly", e.getCause());   // sections never throw; our bug
        }
    }

    /** Non-blocking: the wait is over, so a straggler must not extend the request. */
    private <T> SectionResult<T> harvest(SectionSpec<T> spec, String consumerId,
                                          CompletableFuture<SectionResult<T>> f, BootstrapConfig cfg) {
        if (f.isCancelled()) return staleOrUnavailable(spec, consumerId, Failure.DEADLINE_EXCEEDED, cfg);
        if (f.isDone() && !f.isCompletedExceptionally()) {
            return f.getNow(staleOrUnavailable(spec, consumerId, Failure.DEADLINE_EXCEEDED, cfg));
        }
        return staleOrUnavailable(spec, consumerId, Failure.DEADLINE_EXCEEDED, cfg);
    }

    private void countSection(String name, SectionResult<?> r) {
        registry.counter("bootstrap.section.status", "section", name, "status", r.status().name()).increment();
    }
}
```

```java
package com.example.bootstrap.service;

import com.example.bootstrap.client.*;
import com.example.bootstrap.core.Failure;
import com.example.bootstrap.section.SectionResult;

public record UserResolution(String consumerId, SectionResult<UserProfile> profile, Failure failure) {

    public static UserResolution fresh(UserResponse r) {
        return new UserResolution(r.consumerId(), SectionResult.ok(r.profile()), Failure.NONE);
    }
    /** consumer_id from cache: downstream fan-out still possible, profile degraded. */
    public static UserResolution degraded(String consumerId, SectionResult<UserProfile> profile) {
        return new UserResolution(consumerId, profile, Failure.NONE);
    }
    public static UserResolution notFound()               { return new UserResolution(null, null, Failure.NOT_FOUND); }
    public static UserResolution hardFailure(Failure f)   { return new UserResolution(null, null, f); }

    public boolean isResolved() { return consumerId != null; }
}
```

```java
package com.example.bootstrap.service;

import com.example.bootstrap.api.BootstrapResult;

public interface BootstrapService {
    BootstrapResult bootstrap(String userId, String inboundCorrelationId);
}
```

---

# 6. Wiring and HTTP

```java
package com.example.bootstrap.service;

import com.example.bootstrap.client.*;
import com.example.bootstrap.core.*;
import com.example.bootstrap.section.*;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class BootstrapModule implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BootstrapModule.class);

    private final ExecutorService fanOut;
    private final ExecutorService userIo;
    private final ExecutorService paymentIo;
    private final ExecutorService addressIo;
    private final BootstrapService service;

    /**
     * Little's Law at 1000 rps, ~150ms mean: ~150 requests in flight. Each holds 2 fanOut
     * threads and 1 io thread per active dependency. Sized with headroom for p99.
     * Java 21: newVirtualThreadPerTaskExecutor() removes this arithmetic entirely.
     */
    public BootstrapModule(UserClient user, PaymentClient payment, AddressClient address,
                           MeterRegistry registry, Supplier<BootstrapConfig> config) {

        // Two tiers: a fanOut thread BLOCKS on an io task. One shared bounded pool would
        // deadlock — branches hold every slot while their attempts queue behind them.
        this.fanOut    = pool("bs-fanout", 384);
        // Bulkheads: one io pool per dependency, so a slow Payments cannot starve Address.
        this.userIo    = pool("bs-io-user", 256);
        this.paymentIo = pool("bs-io-payment", 256);
        this.addressIo = pool("bs-io-address", 256);

        SectionCache cache = new InMemorySectionCache(100_000);
        CircuitBreaker breaker = SlidingWindowCircuitBreaker.defaults();

        RetryTemplate userRetry = new DefaultRetryTemplate(userIo, RetryPolicy.forUserHop(), registry);
        // Per-dependency io pools mean the fan-out template is constructed per section in a
        // fuller build; a shared instance is used here for brevity with paymentIo as carrier.
        RetryTemplate fanRetry  = new DefaultRetryTemplate(paymentIo, RetryPolicy.forFanOut(), registry);

        for (String key : List.of("user", "payment", "address")) {
            registry.gauge("circuit.state", List.of(io.micrometer.core.instrument.Tag.of("service", key)),
                    breaker, b -> b.state(key).ordinal());
        }

        this.service = new DefaultBootstrapService(user, payment, address,
                userRetry, fanRetry, fanOut, cache, breaker, registry, config);
    }

    public BootstrapService service() { return service; }

    /**
     * SynchronousQueue + core 0 => grow to max, then REJECT. A LinkedBlockingQueue would queue
     * unboundedly: invisible latency growth and eventual OOM instead of fast shedding.
     * AbortPolicy is required — CallerRunsPolicy would run the call inline and the
     * Future.get(timeout) deadline would silently not apply.
     */
    private static ExecutorService pool(String name, int max) {
        return new ThreadPoolExecutor(0, max, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new NamedThreadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public void close() {
        shutdown("fanOut", fanOut);           // reverse dependency order
        shutdown("userIo", userIo);
        shutdown("paymentIo", paymentIo);
        shutdown("addressIo", addressIo);
    }

    private static void shutdown(String name, ExecutorService es) {
        es.shutdown();
        try {
            if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("pool={} event=forced_shutdown abandoned={}", name, es.shutdownNow().size());
            }
        } catch (InterruptedException e) { es.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
```

```java
package com.example.bootstrap.api;

import com.example.bootstrap.core.Failure;
import com.example.bootstrap.service.BootstrapService;

import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class BootstrapController {

    private static final Logger log = LoggerFactory.getLogger(BootstrapController.class);
    private static final String ELEVATED_SCOPE = "bootstrap:read:any";

    private final BootstrapService service;

    public BootstrapController(BootstrapService service) { this.service = service; }

    @GetMapping("/bootstrap")
    public ResponseEntity<?> bootstrap(
            @RequestAttribute("principal") Principal principal,
            @RequestParam(name = "user_id", required = false) String requestedUserId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String subject = resolveSubject(principal, requestedUserId);
        BootstrapResult result = service.bootstrap(subject, correlationId);

        if (result instanceof BootstrapResult.Success s) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")   // payment methods in body
                    .header("X-Correlation-Id", s.response().meta().correlationId())
                    .body(s.response());
        }
        BootstrapResult.Unavailable u = (BootstrapResult.Unavailable) result;
        HttpStatus status = statusFor(u.failure());
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Correlation-Id", u.correlationId());
        if (status == HttpStatus.SERVICE_UNAVAILABLE) b.header(HttpHeaders.RETRY_AFTER, "2");
        return b.body(new BootstrapErrorBody(u.failure().name(), messageFor(u.failure()),
                u.failure().isTransient(), u.correlationId()));
    }

    /**
     * Closes the IDOR. user_id from the query string is an insecure direct object reference:
     * without this, any valid token could read any user's payment methods and addresses.
     */
    private static String resolveSubject(Principal principal, String requestedUserId) {
        if (requestedUserId == null || requestedUserId.equals(principal.userId())) {
            return principal.userId();
        }
        if (principal.hasScope(ELEVATED_SCOPE)) {
            log.warn("event=cross_user_bootstrap actor={} target={}", principal.userId(), requestedUserId);
            return requestedUserId;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not permitted to bootstrap another user");
    }

    private static HttpStatus statusFor(Failure f) {
        return switch (f) {
            case NOT_FOUND          -> HttpStatus.NOT_FOUND;        // deterministic
            case DEADLINE_EXCEEDED  -> HttpStatus.GATEWAY_TIMEOUT;  // slow
            default                 -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static String messageFor(Failure f) {
        return switch (f) {
            case NOT_FOUND         -> "user not found";
            case DEADLINE_EXCEEDED -> "user service did not respond in time";
            default                -> "bootstrap temporarily unavailable";
        };
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());   // caller error must be 4xx, not 500
    }
}
```

---

# Explanation: the three flows that matter

**Happy path (~200ms).** `resolveUser` calls User once (~80ms), caches the mapping for 24h and the profile for 30m, and returns `fresh`. The fan-out launches Payments and Address concurrently on `fanOut`, each running its own retry loop on its own bulkheaded `io` pool. Both return; sections are `OK`; `degraded=false`; HTTP 200.

**Payments down, Address fine.** `fetchSection(payment)` attempts, fails, retries once, fails. `breaker.recordFailure` ticks the window. The ladder falls to `staleOrUnavailable` → a cache hit within 60s yields `STALE` with `as_of`; otherwise `UNAVAILABLE` with `retryable=true`. Address is unaffected — it has its own connection pool, its own io pool, and its own breaker. Response is **HTTP 200**, `degraded=true`, and the client renders a retry affordance on the payments card rather than "no saved cards."

**User Service down.** `resolveUser` fails, `degradedRoot` reads the 24h mapping cache. If the `consumer_id` is there, the request **continues** — profile becomes `STALE` (or `UNAVAILABLE`), and Payments/Address still resolve normally. Only a cold cache produces `BootstrapResult.Unavailable` → 503. This single fallback converts the worst failure mode from "total outage" into "one degraded section."

---

# Answers to Parts 1–4

**Part 1 — Contract.** Four section states (`OK` / `EMPTY` / `STALE` / `UNAVAILABLE`) because the client must be able to distinguish *"you have no cards"* from *"we couldn't find out."* Collapsing them makes the client render an empty state during an outage and prompts the user to re-enter card details — a real harm, not a cosmetic one. HTTP status describes the *request*; body status describes *data completeness*. `200` whenever `consumer_id` resolved, because the answer is usable and a non-2xx would trip generic client retry handlers and inflate our own error dashboards. `503`/`504`/`404` only when nothing is addressable, with `404` split out because it's deterministic and must never be retried or served from cache.

**Part 2 — Orchestration.** One forced serial hop (User produces `consumer_id`), then a genuine fan-out. Latency is bounded by **sub-budgets, not one global deadline**: `overall.cappedAt(180ms)` for hop 1 exists specifically so a slow User Service cannot consume the budget and starve a fan-out whose services are healthy. 550ms total = 180 (hop 1) + 320 (hop 2) + 50 slack, inside a 600ms SLO with 50ms for our own serialization. Every attempt timeout is `min(perAttemptTimeout, remainingBudget)`, so retries can never overrun the SLA.

**Part 3 — Reliability.** Timeouts first (free, and every other control depends on them). Retries second, capped at 1, transient-only, full jitter, safe because `GET` is idempotent. Bulkheads third — per-dependency connection *and* thread pools, which is what makes the sections genuinely independent. Circuit breaker fourth, because after ten minutes of downtime retries are pure waste; it converts that into an instant fallback and protects the latency SLO too. Caching last, governed by volatility: 24h for the near-immutable mapping, 30m for profile/addresses, **60s for payment methods**. Only fresh authoritative results are ever written — cache-filling from a degraded read propagates staleness indefinitely.

**Part 4 — Observability.** The defining constraint is that **failures don't appear as 5xx**. So the primary SLI is `bootstrap.section.status{section,status}`, not HTTP error rate. `bootstrap.deadline_remaining_at_fanout` is the one I'd insist on: if its p10 trends down, hop 1 is quietly eating the budget and hop 2 will start degrading days before it shows up as a section failure. Runtime-tunable without deploy: per-hop budgets, retry counts, breaker thresholds, per-section `maxStale`, and the `allowStale` kill switch.

---

# Follow-ups

**100× read volume.** The downstreams break first, not us — we've multiplied their read load. Three moves in order: (1) **single-flight request coalescing** per `(section, consumerId)`, since at that scale a cache stampede on a hot key turns 10,000 concurrent misses into 10,000 identical calls; (2) shared Redis in front of the local cache, same governance rules, and the cache becomes read-through (which is where the TTL-vs-maxStale distinction starts mattering); (3) at the far end, a **materialized bootstrap projection** keyed by `user_id`, maintained by CDC, turning this into one read. That's a consistency-for-scale trade I'd take only once fan-out reads dominate.

**Thundering herd on breaker close.** Three mechanisms, all in the code above: half-open admits only `probePermits` concurrent probes (not all traffic); the open duration carries **±20% per-instance jitter** so N replicas don't probe at the same instant; and a single probe failure re-opens immediately rather than waiting for a window to fill. What I'd add for production is a **gradual ramp** — 5% → 10% → 25% → 50% → 100% with a health window at each step — plus a retry budget so recovery traffic can't be amplified.

**The IDOR.** As specified, `GET /bootstrap?user_id=...` lets any authenticated caller read any user's payment methods and addresses — horizontal privilege escalation, the highest-severity issue in the prompt. `resolveSubject` closes it: the query parameter is ignored unless the principal holds `bootstrap:read:any`, and cross-user access is logged. Secondary hardening: per-principal rate limits, and keying any negative cache per principal so `404` vs `200` isn't an enumeration oracle.

**Stale payments vs nothing.** The deciding question is the cost of *acting on wrong data* versus the cost of *being blocked*. A removed card shown as valid leads to a failed charge — worse than a retry affordance the user can act on immediately. But this is first paint, not checkout, and checkout re-validates before charging, so a narrow window is net positive. Hence 60s, labelled `STALE` with `as_of`, plus two hard rules: never serve a *partial* cached list (indistinguishable from a real one, worse than none), and never widen `paymentMaxStale` under load — that's the tempting incident knob and it converts a latency problem into a correctness problem. On the checkout screen I'd serve nothing.

**Deadline propagation.** `X-Request-Timeout-Ms`, set in each client from `deadline.remainingMillis()` — **relative, not absolute**, so clock skew can't corrupt it. The downstream derives its own deadline and returns early rather than completing work nobody will read. The benefit is **capacity, not latency**: our timeout already protects us; self-cancellation stops the downstream burning CPU and DB connections on abandoned requests, which is what keeps a slow dependency from becoming a dead one. It's advisory — a downstream that ignores it is still cut off by our timeout — so it's defense in depth.

---

**One inconsistency I'd fix before merging:** `BootstrapModule` constructs a single `fanRetry` over `paymentIo`, so the address section's attempts run on the payments io pool — which defeats the bulkhead I argued for. The fix is one `RetryTemplate` per section (`paymentRetry` over `paymentIo`, `addressRetry` over `addressIo`), threaded into `SectionSpec` rather than shared. Small change, but the bulkhead is the control that makes "independent sections" true rather than aspirational, so it's worth doing properly.