import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class BootstrapAggregator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAggregator.class);
    private static final String CORRELATION_ID = "correlationId";
    private static final String DEFAULT_ADDRESS = "";

    private final UserService userService;
    private final PaymentService paymentService;
    private final AddressService addressService;
    private final ResilientCaller caller;
    private final ExecutorService fanOut;
    private final ExecutorService io;

    public BootstrapAggregator(UserService u, PaymentService p, AddressService a) {
        this(u, p, a, Policy.defaults(), 64, 64);
    }

    public BootstrapAggregator(UserService u, PaymentService p, AddressService a,
                               Policy policy, int maxFanOutThreads, int maxIoThreads) {
        this.userService    = Objects.requireNonNull(u, "userService");
        this.paymentService = Objects.requireNonNull(p, "paymentService");
        this.addressService = Objects.requireNonNull(a, "addressService");
        if (maxFanOutThreads < 1 || maxIoThreads < 1) {
            throw new IllegalArgumentException("pool sizes must be >= 1");
        }
        // Separate pools: a fanOut thread blocks on an io task. One shared bounded pool would
        // deadlock under load — branches holding every slot, attempts queued behind them.
        this.fanOut = newElasticPool("bootstrap-fanout", maxFanOutThreads);
        this.io     = newElasticPool("bootstrap-io", maxIoThreads);
        this.caller = new ResilientCaller(io, policy);
    }

    private static ExecutorService newElasticPool(String name, int max) {
        return new ThreadPoolExecutor(
                0, max, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),          // grow to max, then reject (never queue unboundedly)
                new NamedThreadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());     // CallerRunsPolicy would run the call
                                                           // inline and silently void the timeout
    }

    /**
     * @throws IllegalArgumentException if userId is null or blank — a caller bug, not a
     *         service failure, and worth failing loudly rather than burning a UserService call.
     * @return empty when the bootstrap cannot proceed (UserService unresolved, or shutdown
     *         in progress). Partial downstream failures degrade to documented defaults instead.
     */
    public Optional<BootstrapResponse> bootstrap(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId must not be null or blank");
        }

        final long start = System.nanoTime();
        // Don't clobber a correlation id set by an upstream servlet filter; only clean up what we own.
        final boolean ownsCorrelationId = MDC.get(CORRELATION_ID) == null;
        if (ownsCorrelationId) MDC.put(CORRELATION_ID, UUID.randomUUID().toString());

        try {
            CallOutcome<UserResponse> user = caller.call("UserService",
                    () -> userService.getResponse(new UserRequest(userId)));

            if (!user.isSuccess()) {
                log.warn("event=bootstrap_aborted userId={} reason={} elapsedMs={}",
                        userId, user.getFailure(), elapsedMs(start));
                return Optional.empty();
            }
            final String customerId = user.getValue().getCustomerId();
            if (customerId == null || customerId.trim().isEmpty()) {
                // 200 with an empty body field. Without this, null flows into both downstream
                // requests, they 4xx, and one upstream bug looks like a three-service outage.
                log.error("event=bootstrap_aborted userId={} reason=null_customer_id status={} elapsedMs={}",
                        userId, user.getValue().getStatusCode(), elapsedMs(start));
                return Optional.empty();
            }

            CompletableFuture<CallOutcome<PaymentResponse>> paymentF = submitBranch("PaymentService",
                    () -> caller.call("PaymentService",
                            () -> paymentService.getResponse(new PaymentRequest(customerId))));

            CompletableFuture<CallOutcome<AddressResponse>> addressF = submitBranch("AddressService",
                    () -> caller.call("AddressService",
                            () -> addressService.getResponse(new AddressRequest(customerId))));

            CallOutcome<PaymentResponse> payment = joinBranch("PaymentService", paymentF);
            CallOutcome<AddressResponse> address = joinBranch("AddressService", addressF);

            // An interrupt is NOT a degradable failure. Serving defaultCard=null during shutdown
            // tells the user "no card on file", which is a wrong answer dressed up as a
            // degraded one. Fail the request honestly and let the caller return 503.
            if (payment.getFailure() == CallOutcome.Failure.INTERRUPTED
                    || address.getFailure() == CallOutcome.Failure.INTERRUPTED) {
                Thread.currentThread().interrupt();
                log.warn("event=bootstrap_aborted customerId={} reason=interrupted elapsedMs={}",
                        customerId, elapsedMs(start));
                return Optional.empty();
            }

            DefaultCard card = payment.toOptional().map(DefaultCard::from).orElse(null);
            String addr      = address.toOptional().map(AddressResponse::getAddress).orElse(DEFAULT_ADDRESS);

            if (!payment.isSuccess()) {
                log.warn("event=degraded field=defaultCard customerId={} reason={} attempts={}",
                        customerId, payment.getFailure(), payment.getAttempts());
            }
            if (!address.isSuccess()) {
                log.warn("event=degraded field=address customerId={} reason={} attempts={}",
                        customerId, address.getFailure(), address.getAttempts());
            }

            // Booleans, not values — the card and address are PII and must not reach the log.
            log.info("event=bootstrap_complete customerId={} hasCard={} hasAddress={} "
                            + "elapsedMs={} userAttempts={} paymentAttempts={} addressAttempts={}",
                    customerId, card != null, !addr.isEmpty(), elapsedMs(start),
                    user.getAttempts(), payment.getAttempts(), address.getAttempts());

            return Optional.of(new BootstrapResponse(customerId, card, addr));

        } finally {
            if (ownsCorrelationId) MDC.remove(CORRELATION_ID);
        }
    }

    private <T> CompletableFuture<CallOutcome<T>> submitBranch(String op, Supplier<CallOutcome<T>> work) {
        try {
            return CompletableFuture.supplyAsync(work, fanOut);
        } catch (RejectedExecutionException e) {
            // supplyAsync calls execute() synchronously, so rejection lands here, not in the future.
            log.error("op={} event=branch_rejected reason=fanout_pool_saturated_or_shutdown", op);
            return CompletableFuture.completedFuture(
                    CallOutcome.<T>failure(CallOutcome.Failure.REJECTED, 0, 0));
        }
    }

    private static <T> CallOutcome<T> joinBranch(String op, CompletableFuture<CallOutcome<T>> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof Error) throw (Error) cause;   // same rule: never mask a fatal condition
            // ResilientCaller shouldn't throw; if it did, that is a bug in our code and the
            // stack trace is the whole point of this catch.
            log.error("op={} event=branch_failed_unexpectedly", op, cause);
            return CallOutcome.<T>failure(CallOutcome.Failure.NON_RETRYABLE_ERROR, 0, 0);
        } catch (CancellationException e) {
            log.warn("op={} event=branch_cancelled", op);
            return CallOutcome.<T>failure(CallOutcome.Failure.INTERRUPTED, 0, 0);
        }
    }

    @Override public void close() {
        log.info("event=shutdown_start");
        shutdown("fanOut", fanOut);
        shutdown("io", io);          // reverse dependency order: branches depend on io
        log.info("event=shutdown_complete");
    }

    private static void shutdown(String name, ExecutorService es) {
        es.shutdown();
        try {
            if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("pool={} event=forced_shutdown abandonedTasks={}", name, es.shutdownNow().size());
            }
        } catch (InterruptedException e) {
            log.warn("pool={} event=shutdown_interrupted", name);
            es.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}