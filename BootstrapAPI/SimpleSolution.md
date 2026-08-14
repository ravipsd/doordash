Here is a comprehensive design and implementation for a highly resilient Bootstrap API.

### 1. Assumptions & Tunable Parameters

To meet the p99 ≤ 600ms latency requirement and maximize resilience, I am anchoring the design on the following tunable constraints:

* **Concurrency**: All downstream calls must be executed in parallel (Scatter-Gather pattern).
* **Timeouts**:
* **Global API SLA**: 600ms.
* **Downstream Timeout**: 450ms per service. This leaves 150ms for network overhead, TLS handshakes, thread context switching, and JSON serialization.


* **Retries**: Max **1 retry** on immediate failures (e.g., `Connection Refused` or HTTP 503). If a request *times out*, we **do not** retry, as we would blow past the 600ms global SLA.
* **Circuit Breakers**: Configured per downstream service. If a service experiences a >50% failure rate over 100 requests, the circuit opens for 30 seconds, immediately short-circuiting future calls to a fallback.
* **Graceful Degradation**: If a service fails, times out, or the circuit is open, the API will return a `200 OK` with `null` for that specific data block, appending an error warning in a `metadata` field so the client UI knows to show placeholders (e.g., a grayed-out payment method).
* **Thread Pools**: We will use a bounded thread pool (e.g., 200 threads) with a finite queue (e.g., 500) and a `RejectExecutionHandler` that falls back to returning partial data rather than crashing the server.

---

### 2. Response Contract

The client needs to know if the response is complete or degraded.

```json
{
  "data": {
    "profile": { "name": "Jane", "email": "jane@example.com" },
    "payment": null, 
    "address": { "city": "Seattle", "zip": "98101" }
  },
  "metadata": {
    "is_degraded": true,
    "failed_services": ["payment"]
  }
}

```

---

### 3. Java Implementation

This implementation uses Java's `CompletableFuture` to handle parallel execution, asynchronous timeouts, and fallback logic natively.

```java
import java.util.concurrent.*;
import java.util.*;

public class BootstrapService {

    // Bounded thread pool to prevent cascading resource exhaustion
    private final ExecutorService executor = new ThreadPoolExecutor(
            50, 200, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500),
            new ThreadPoolExecutor.AbortPolicy()
    );

    private final ProfileClient profileClient;
    private final PaymentClient paymentClient;
    private final AddressClient addressClient;

    // Tunable SLA parameters
    private static final long DOWNSTREAM_TIMEOUT_MS = 450;

    public BootstrapService(ProfileClient profileClient, PaymentClient paymentClient, AddressClient addressClient) {
        this.profileClient = profileClient;
        this.paymentClient = paymentClient;
        this.addressClient = addressClient;
    }

    /**
     * Primary endpoint logic for GET /bootstrap?user_id=...
     */
    public BootstrapResponse getBootstrapData(String userId) {
        
        // 1. Kick off parallel async calls to downstreams
        CompletableFuture<Optional<Profile>> profileFuture = fetchWithResilience(
                () -> profileClient.getProfile(userId), "profile"
        );
        
        CompletableFuture<Optional<Payment>> paymentFuture = fetchWithResilience(
                () -> paymentClient.getPayment(userId), "payment"
        );
        
        CompletableFuture<Optional<Address>> addressFuture = fetchWithResilience(
                () -> addressClient.getAddress(userId), "address"
        );

        // 2. Wait for ALL futures to complete (either successfully, via timeout, or via fallback)
        CompletableFuture.allOf(profileFuture, paymentFuture, addressFuture).join();

        // 3. Aggregate results and build the graceful degradation metadata
        BootstrapResponse response = new BootstrapResponse();
        List<String> failedServices = new ArrayList<>();

        // Extract Profile
        Optional<Profile> profileOpt = profileFuture.join();
        if (profileOpt.isPresent()) {
            response.setProfile(profileOpt.get());
        } else {
            failedServices.add("profile");
        }

        // Extract Payment
        Optional<Payment> paymentOpt = paymentFuture.join();
        if (paymentOpt.isPresent()) {
            response.setPayment(paymentOpt.get());
        } else {
            failedServices.add("payment");
        }

        // Extract Address
        Optional<Address> addressOpt = addressFuture.join();
        if (addressOpt.isPresent()) {
            response.setAddress(addressOpt.get());
        } else {
            failedServices.add("address");
        }

        // 4. Attach metadata indicating partial failures
        response.setMetadata(new Metadata(!failedServices.isEmpty(), failedServices));

        return response;
    }

    /**
     * Wraps a downstream call with timeouts, threading, and exception swallowing 
     * to ensure graceful degradation.
     */
    private <T> CompletableFuture<Optional<T>> fetchWithResilience(Callable<T> downstreamCall, String serviceName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // In a real app, circuit breaker logic (e.g., Resilience4j) wraps this call
                return Optional.ofNullable(downstreamCall.call());
            } catch (Exception e) {
                // Log failure (metrics, Datadog, etc.)
                System.err.println(serviceName + " call failed: " + e.getMessage());
                throw new CompletionException(e);
            }
        }, executor)
        // Enforce tight downstream timeout
        .orTimeout(DOWNSTREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // Graceful fallback: If it errors or times out, swallow it and return empty
        .exceptionally(ex -> {
            System.err.println(serviceName + " degraded due to: " + ex.getMessage());
            // Optional: If we had a Redis/Local Cache, we could attempt a stale read here.
            return Optional.empty();
        });
    }
}

```

### 4. System Design & Architectural Safeguards

To make this production-ready, the code above sits inside a larger ecosystem. Here is how the system guards itself:

#### A. Bulkheading (Thread Isolation)

If the `Payment` service experiences an outage and hangs for every request, it will rapidly consume threads. We mitigate this by using a bounded `ThreadPoolExecutor`. In a more advanced setup, you would create three *separate* smaller thread pools (Bulkheads)—one for each service—so a blocked Payment API cannot starve the threads needed to fetch Profile and Address data.

#### B. The Circuit Breaker Pattern (Resilience4j)

While the `exceptionally()` block in the code handles errors, repeated timeouts will still consume resources for 450ms. A circuit breaker (like Resilience4j) wraps the `downstreamCall.call()`.

* **Closed**: Normal operation.
* **Open**: If >50% of requests fail or timeout, the breaker trips. Future calls immediately throw a `CallNotPermittedException` (0ms latency) which drops into our `.exceptionally()` block, saving the 450ms wait and immediately returning partial data to the client.
* **Half-Open**: After 30 seconds, it allows a few test requests through to see if the downstream has recovered.

#### C. Stale Cache Fallback (Optional but Recommended)

For data that rarely changes (like User Profile or Address), we can insert a caching tier.
If the primary `profileClient.getProfile()` fails or times out, the `.exceptionally()` block can make a lightning-fast (e.g., 10ms timeout) call to a Redis cluster to fetch the last known state. This allows the API to return complete data even when downstreams are hard-down, turning a "partial failure" into a "stale success".

You are absolutely right, and that is a critical architectural distinction. I missed the sequential dependency constraint.

Because the User Service is a **forced prerequisite**, we can no longer fire all three requests in parallel at the start. This fundamentally changes our latency budget and creates a Single Point of Failure (SPOF) for the downstream calls.

Here is how the design and implementation must adapt to this directed acyclic graph (DAG) dependency.

### 1. Adjusted Assumptions & Latency Budget

Because we are forced into a sequential two-step fetch (`User -> [Payment, Address]`), we must split our 600ms p99 budget:

* **User Service Timeout:** **250ms**. Since everything depends on this, it needs to be tight.
* **Downstream Timeout (Payment/Address):** **250ms**. Executed in parallel *after* the User Service returns.
* **Buffer:** **100ms** remaining for network transit, TLS handshakes, JSON serialization, and thread context switching.
* **Cascading Degradation:** If the User Service fails or times out, we cannot fetch Payment or Address. The endpoint will degrade gracefully by returning `null` for *all* data blocks and flagging all three services as failed in the metadata.

---

### 2. Updated Java Implementation

This revised implementation executes the User Service call first, extracts the `consumerId`, and then fans out to the Payment and Address services in parallel.

```java
import java.util.concurrent.*;
import java.util.*;

public class BootstrapService {

    private final ExecutorService executor = new ThreadPoolExecutor(
            50, 200, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500),
            new ThreadPoolExecutor.AbortPolicy()
    );

    private final UserService userService;
    private final PaymentClient paymentClient;
    private final AddressClient addressClient;

    // Split Latency Budgets
    private static final long USER_TIMEOUT_MS = 250;
    private static final long DOWNSTREAM_TIMEOUT_MS = 250;

    public BootstrapService(UserService userService, PaymentClient paymentClient, AddressClient addressClient) {
        this.userService = userService;
        this.paymentClient = paymentClient;
        this.addressClient = addressClient;
    }

    public BootstrapResponse getBootstrapData(String userId) {
        BootstrapResponse response = new BootstrapResponse();
        List<String> failedServices = new ArrayList<>();

        // 1. Fetch User Service first (Critical Path)
        CompletableFuture<Optional<User>> userFuture = fetchWithResilience(
                () -> userService.getUser(userId), "user", USER_TIMEOUT_MS
        );

        Optional<User> userOpt = userFuture.join();

        // 2. Cascading Failure Check
        if (userOpt.isEmpty() || userOpt.get().getConsumerId() == null) {
            // User service failed. We cannot route to downstreams. 
            // Gracefully degrade the entire response.
            response.setMetadata(new Metadata(true, Arrays.asList("user", "payment", "address")));
            return response;
        }

        User user = userOpt.get();
        response.setUser(user);
        String consumerId = user.getConsumerId();

        // 3. Fan-out to dependent services in parallel using the retrieved consumer_id
        CompletableFuture<Optional<Payment>> paymentFuture = fetchWithResilience(
                () -> paymentClient.getPayment(consumerId), "payment", DOWNSTREAM_TIMEOUT_MS
        );
        
        CompletableFuture<Optional<Address>> addressFuture = fetchWithResilience(
                () -> addressClient.getAddress(consumerId), "address", DOWNSTREAM_TIMEOUT_MS
        );

        // Wait for parallel downstream fetches to complete
        CompletableFuture.allOf(paymentFuture, addressFuture).join();

        // 4. Aggregate downstream results
        Optional<Payment> paymentOpt = paymentFuture.join();
        if (paymentOpt.isPresent()) {
            response.setPayment(paymentOpt.get());
        } else {
            failedServices.add("payment");
        }

        Optional<Address> addressOpt = addressFuture.join();
        if (addressOpt.isPresent()) {
            response.setAddress(addressOpt.get());
        } else {
            failedServices.add("address");
        }

        // 5. Attach metadata
        response.setMetadata(new Metadata(!failedServices.isEmpty(), failedServices));

        return response;
    }

    private <T> CompletableFuture<Optional<T>> fetchWithResilience(
            Callable<T> call, String serviceName, long timeoutMs) {
                
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Optional.ofNullable(call.call());
            } catch (Exception e) {
                System.err.println(serviceName + " call failed: " + e.getMessage());
                throw new CompletionException(e);
            }
        }, executor)
        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .exceptionally(ex -> {
            System.err.println(serviceName + " degraded due to: " + ex.getMessage());
            return Optional.empty();
        });
    }
}

```

---

### 3. Architectural Implications of the Dependency

By introducing a strict dependency chain, the system's resilience profile shifts significantly:

1. **The User Service is now a hard bottleneck.** Because 100% of Bootstrap requests rely on it returning a `consumer_id` quickly, its uptime dictates the usefulness of the entire API.
2. **Aggressive Caching is mandatory here.** To protect the critical path, the `UserService` response (mapping `user_id` to `consumer_id` and profile data) should be heavily cached in a low-latency store like Redis or Memcached. If the live User Service API times out after 250ms, the `.exceptionally()` fallback should instantly query Redis as a stale-read fallback, preventing the cascading failure of the Payments and Address services.


To add exponential backoff without destroying your system's performance, the most critical rule is **never block the thread**. If you use `Thread.sleep()` inside a `CompletableFuture`, you will quickly exhaust your worker pool during an outage and cause a cascading failure.

Because your original code uses `.orTimeout()` (introduced in Java 9), we can take advantage of modern Java concurrency features—specifically `CompletableFuture.delayedExecutor()` (Java 9) and `exceptionallyCompose()` (Java 12)—to build a completely non-blocking, recursive retry loop.

Here is the production-ready rewrite:

```java
import java.util.Optional;
import java.util.concurrent.*;

public class ResilientClient {

    // Assume this is injected or defined at the class level
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    /**
     * Primary entry point: Handles the overall timeout and final graceful degradation.
     */
    public <T> CompletableFuture<Optional<T>> fetchWithResilience(
            Callable<T> call, String serviceName, long timeoutMs) {
                
        int maxRetries = 3;
        long initialBackoffMs = 100L;

        // Start the recursive retry engine
        return executeWithRetry(call, serviceName, maxRetries, initialBackoffMs, 1)
                // Global Timeout: If all retries combined exceed this limit, abort.
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                // Final Fallback: If retries are exhausted OR the global timeout hits, degrade gracefully.
                .exceptionally(ex -> {
                    System.err.println(serviceName + " degraded completely: " + ex.getMessage());
                    return Optional.empty();
                });
    }

    /**
     * Recursive async retry engine with exponential backoff.
     */
    private <T> CompletableFuture<Optional<T>> executeWithRetry(
            Callable<T> call, String serviceName, int maxRetries, long backoffMs, int attempt) {
                
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Optional.ofNullable(call.call());
            } catch (Exception e) {
                // Wrap checked exceptions to trigger the exceptionallyCompose block
                throw new CompletionException(e);
            }
        }, executor)
        .exceptionallyCompose(ex -> {
            // 1. Base Case: Stop retrying if we hit the limit
            if (attempt >= maxRetries) {
                System.err.println(serviceName + " failed permanently after " + attempt + " attempts.");
                return CompletableFuture.failedFuture(ex); 
            }

            System.err.println(serviceName + " attempt " + attempt + " failed. Retrying in " + backoffMs + "ms...");

            // 2. The Backoff: Create an executor that waits asynchronously (Non-blocking)
            Executor delayedExecutor = CompletableFuture.delayedExecutor(backoffMs, TimeUnit.MILLISECONDS, executor);

            // 3. The Recursion: Wait on the delayed executor, then trigger the next attempt
            return CompletableFuture.supplyAsync(() -> null, delayedExecutor)
                    .thenCompose(v -> executeWithRetry(call, serviceName, maxRetries, backoffMs * 2, attempt + 1));
        });
    }
}

```

### Why this architecture stands out in an interview:

1. **Purely Asynchronous Wait:** By using `CompletableFuture.delayedExecutor()`, the thread immediately returns to the `executor` pool while the backoff timer counts down. The thread is only consumed when the actual network call is happening.
2. **`exceptionallyCompose`:** Older Java 8 code required ugly hacks to chain a new `CompletableFuture` after a failure. `exceptionallyCompose` acts just like `thenCompose`, but specifically routes the failure path back into a fresh asynchronous retry.
3. **Global vs. Local Timeouts:** The structure separates the retry logic from the timeout logic. The `.orTimeout()` applies to the *entire chain* of events. If your initial call takes 2 seconds, and your backoff waits 1 second, a 2.5-second `timeoutMs` will successfully interrupt the whole process and trigger the final fallback, ensuring your frontend never hangs.

