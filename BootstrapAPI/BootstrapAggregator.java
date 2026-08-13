import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;

public final class BootstrapAggregator implements AutoCloseable {

    private static final String DEFAULT_ADDRESS = "";   // documented default for a missing address

    private final UserService userService;
    private final PaymentService paymentService;
    private final AddressService addressService;

    private final ResilientCaller caller;
    private final ExecutorService fanOut;   // runs the two independent branches
    private final ExecutorService io;       // runs the individual blocking attempts

    public BootstrapAggregator(UserService u, PaymentService p, AddressService a) {
        this(u, p, a, ResilientCaller.Policy.defaults(), 64, 64);
    }

    /**
     * @param maxFanOutThreads branch threads; each in-flight bootstrap uses 2. Size >= 2 x peak concurrency.
     * @param maxIoThreads     attempt threads; each in-flight bootstrap uses up to 2. Size >= 2 x peak concurrency.
     */
    public BootstrapAggregator(UserService u, PaymentService p, AddressService a,
                               ResilientCaller.Policy policy,
                               int maxFanOutThreads, int maxIoThreads) {
        this.userService    = Objects.requireNonNull(u);
        this.paymentService = Objects.requireNonNull(p);
        this.addressService = Objects.requireNonNull(a);

        // TWO SEPARATE POOLS, and this is not optional.
        // A fanOut thread blocks waiting on an io task. Share one bounded pool and the branch
        // threads can occupy every slot while the attempts they await sit queued behind them —
        // classic pool-induced deadlock, unresolvable until the timeouts fire.
        this.fanOut = newElasticPool("bootstrap-fanout", maxFanOutThreads);
        this.io     = newElasticPool("bootstrap-io", maxIoThreads);
        this.caller = new ResilientCaller(io, policy);
    }

    /**
     * SynchronousQueue + corePoolSize 0 => a cached pool that GROWS to max, then rejects.
     * A LinkedBlockingQueue would queue instead of reject, which for I/O work means unbounded
     * latency and an eventual OOM rather than fast, visible load shedding.
     * AbortPolicy is deliberate: CallerRunsPolicy would execute the HTTP call inline on the
     * caller thread, so Future.get(timeout) would see an already-completed future and the
     * 2s deadline would silently not be enforced.
     */
    private static ExecutorService newElasticPool(String name, int max) {
        return new ThreadPoolExecutor(
                0, max,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new NamedThreadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * @return empty when UserService cannot be resolved — without a customerId there is nothing
     *         to key the downstream calls on, so the bootstrap genuinely cannot proceed.
     */
    public Optional<BootstrapResponse> bootstrap(String userId) {
        Optional<UserResponse> user = caller.call("UserService",
                () -> userService.getResponse(new UserRequest(userId)));
        if (!user.isPresent() || user.get().getCustomerId() == null) {
            return Optional.empty();   // hard dependency
        }
        final String customerId = user.get().getCustomerId();

        // Payment and Address share only customerId — run them concurrently.
        CompletableFuture<Optional<PaymentResponse>> paymentF = submitBranch(
                () -> caller.call("PaymentService",
                        () -> paymentService.getResponse(new PaymentRequest(customerId))));

        CompletableFuture<Optional<AddressResponse>> addressF = submitBranch(
                () -> caller.call("AddressService",
                        () -> addressService.getResponse(new AddressRequest(customerId))));

        // Both branches are already bounded by the policy's total budget, so these joins terminate.
        DefaultCard card = joinOrEmpty(paymentF).map(DefaultCard::from).orElse(null);
        String address   = joinOrEmpty(addressF).map(AddressResponse::getAddress).orElse(DEFAULT_ADDRESS);

        // Built on the caller thread after both futures are joined, and BootstrapResponse is
        // immutable, so no lock or concurrent container is needed for the aggregate itself.
        return Optional.of(new BootstrapResponse(customerId, card, address));
    }

    /**
     * CompletableFuture.supplyAsync throws RejectedExecutionException synchronously when the
     * executor refuses. Degrade that branch instead of failing a bootstrap that could still
     * return partial data.
     */
    private <T> CompletableFuture<Optional<T>> submitBranch(Supplier<Optional<T>> work) {
        try {
            return CompletableFuture.supplyAsync(work, fanOut);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(Optional.<T>empty());
        }
    }

    private static <T> Optional<T> joinOrEmpty(CompletableFuture<Optional<T>> f) {
        try {
            return f.join();
        } catch (CompletionException | CancellationException e) {
            return Optional.empty();   // degrade to the documented default
        }
    }

    @Override public void close() {
        shutdown(fanOut);
        shutdown(io);
    }

    private static void shutdown(ExecutorService es) {
        es.shutdown();
        try {
            if (!es.awaitTermination(5, TimeUnit.SECONDS)) es.shutdownNow();
        } catch (InterruptedException e) {
            es.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}