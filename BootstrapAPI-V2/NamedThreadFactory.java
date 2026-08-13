final class NamedThreadFactory implements ThreadFactory {
    private static final Logger log = LoggerFactory.getLogger(NamedThreadFactory.class);

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();

    NamedThreadFactory(String prefix) { this.prefix = prefix; }

    @Override public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
        t.setDaemon(true);
        // Rarely fires for submit() (exceptions land in the Future), but it is the only net
        // under an Error or anything routed via execute(). Without it, a thread dies silently.
        t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("event=uncaught_exception thread={}", thread.getName(), ex));
        return t;
    }
}