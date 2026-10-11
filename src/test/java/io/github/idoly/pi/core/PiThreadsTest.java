package io.github.idoly.pi.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PiThreadsTest {
    @Test
    void usesDaemonIoWorkers() throws Exception {
        boolean daemon = PiThreads.executor()
                .submit(() -> Thread.currentThread().isDaemon())
                .get(5, TimeUnit.SECONDS);

        assertTrue(daemon);
    }
}
