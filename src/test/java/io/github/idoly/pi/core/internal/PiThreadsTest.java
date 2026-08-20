package io.github.idoly.pi.core.internal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiThreadsTest {
    @Test
    void usesSharedDaemonExecutors() throws Exception {
        boolean daemon = PiThreads.executor()
                .submit(() -> Thread.currentThread().isDaemon())
                .get(5, TimeUnit.SECONDS);

        assertTrue(daemon);
        assertSame(PiThreads.executor(), PiThreads.executor());
        assertSame(PiThreads.scheduler(), PiThreads.scheduler());
    }
}
