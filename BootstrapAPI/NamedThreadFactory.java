import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Named daemon threads: readable thread dumps, and a forgotten close() can't wedge JVM exit. */
final class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();

    NamedThreadFactory(String prefix) { this.prefix = prefix; }

    @Override public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
        t.setDaemon(true);
        return t;
    }
}