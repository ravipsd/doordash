package com.example.aggregation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class NamedThreadFactory implements ThreadFactory {

    private static final Logger log = LoggerFactory.getLogger(NamedThreadFactory.class);

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();   // newThread is called concurrently

    NamedThreadFactory(String prefix) { this.prefix = prefix; }

    @Override public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
        t.setDaemon(true);   // a forgotten shutdown must not wedge JVM exit
        t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("event=uncaught_exception thread={}", thread.getName(), ex));
        return t;
    }
}