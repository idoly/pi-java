package io.github.idoly.pi.core.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

final class PiThreads {
    private static final Pool POOL = createPool();
    private static final ScheduledExecutorService SCHEDULER = createScheduler();

    private PiThreads() {
    }

    static ExecutorService executor() {
        return POOL.executor();
    }

    static ScheduledExecutorService scheduler() {
        return SCHEDULER;
    }

    static boolean virtual() {
        return POOL.virtual();
    }

    private static Pool createPool() {
        try {
            Method factory = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return new Pool((ExecutorService) factory.invoke(null), true);
        } catch (NoSuchMethodException ignored) {
            return new Pool(Executors.newCachedThreadPool(threadFactory("pi-worker-")), false);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static ScheduledExecutorService createScheduler() {
        int size = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ScheduledThreadPoolExecutor scheduler =
                new ScheduledThreadPoolExecutor(size, threadFactory("pi-scheduler-"));
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

    private record Pool(ExecutorService executor, boolean virtual) {
    }
}
