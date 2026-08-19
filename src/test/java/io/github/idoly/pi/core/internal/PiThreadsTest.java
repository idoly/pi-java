package io.github.idoly.pi.core.internal;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PiThreadsTest {
    @Test
    void selectsExecutorForRuntime() throws Exception {
        boolean virtual = PiThreads.executor().submit(() -> {
            try {
                Method isVirtual = Thread.class.getMethod("isVirtual");
                return (boolean) isVirtual.invoke(Thread.currentThread());
            } catch (NoSuchMethodException ignored) {
                return false;
            }
        }).get(5, TimeUnit.SECONDS);

        assertEquals(Runtime.version().feature() >= 21, virtual);
        assertEquals(virtual, PiThreads.virtual());
        assertSame(PiThreads.executor(), PiThreads.executor());
        assertSame(PiThreads.scheduler(), PiThreads.scheduler());
    }
}
