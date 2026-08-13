package com.example.aggregation;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** Owns the executors. Manual wiring; swap for @Bean methods under Spring. */
public final class AggregationModule implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AggregationModule.class);

    private final ExecutorService fanOut;
    private final ExecutorService io;
    private final AggregationService service;

    /**
     * Little's Law: at 500 rps with ~200ms mean latency, ~100 requests are in flight, and each
     * holds 3 fanOut + 3 io threads => ~300 of each at steady state. 384 leaves headroom for p99.
     * On Java 21, Executors.newVirtualThreadPerTaskExecutor() removes this arithmetic entirely.
     */
    public AggregationModule(UpstreamClient<AData> a, UpstreamClient<BData> b, UpstreamClient<CData> c,
                             MeterRegistry registry, Supplier<AggregationConfig> config,
                             RetryPolicy retryPolicy) {
        // TWO pools, not one: a fanOut thread blocks on an io task. Sharing one bounded pool
        // deadlocks under load — branches hold every slot while their attempts queue behind them.
        this.fanOut = newElasticPool("agg-fanout", 384);
        this.io     = newElasticPool("agg-io", 384);
        RetryTemplate retry = new DefaultRetryTemplate(io, retryPolicy, registry);
        this.service = new DefaultAggregationService(a, b, c, retry, fanOut, registry, config);
    }

    public AggregationService service() { return service; }

    /**
     * SynchronousQueue + corePoolSize 0 => grow to max, then REJECT. A LinkedBlockingQueue would
     * queue unboundedly: invisible latency growth and eventual OOM instead of fast load shedding.
     * AbortPolicy is required — CallerRunsPolicy would run the HTTP call inline, so
     * Future.get(timeout) would see an already-completed future and the timeout would not apply.
     */
    private static ExecutorService newElasticPool(String name, int max) {
        return new ThreadPoolExecutor(0, max, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new NamedThreadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public void close() {
        log.info("event=shutdown_start");
        shutdown("fanOut", fanOut);   // reverse dependency order: branches depend on io
        shutdown("io", io);
        log.info("event=shutdown_complete");
    }

    private static void shutdown(String name, ExecutorService es) {
        es.shutdown();
        try {
            if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("pool={} event=forced_shutdown abandonedTasks={}", name, es.shutdownNow().size());
            }
        } catch (InterruptedException e) {
            es.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}