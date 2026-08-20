package io.github.idoly.pi.core.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

final class PiThreads {
    private static final ExecutorService EXECUTOR =
            Executors.newCachedThreadPool(threadFactory("pi-worker-"));
    private static final ScheduledThreadPoolExecutor SCHEDULER = createScheduler();

    private PiThreads() {
    }

    static ExecutorService executor() {
        return EXECUTOR;
    }

    static ScheduledExecutorService scheduler() {
        return SCHEDULER;
    }

    private static ScheduledThreadPoolExecutor createScheduler() {
        ScheduledThreadPoolExecutor scheduler =
                new ScheduledThreadPoolExecutor(1, threadFactory("pi-scheduler-"));
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private static ThreadFactory threadFactory(String prefix) {
        AtomicLong sequence = new AtomicLong();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
