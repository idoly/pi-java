package io.github.idoly.pi.core.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiProcessTest {
    @Test
    void communicatesWithRealChildProcessAndDrainsStderr() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        List<String> stderr = new CopyOnWriteArrayList<>();
        PiProcessConfig processOptions = new PiProcessConfig(
                List.of(
                        java.toString(),
                        "-cp", System.getProperty("java.class.path"),
                        PiTestProcess.class.getName()
                ),
                Path.of(System.getProperty("user.dir")),
                Map.of(),
                8,
                16,
                1024 * 1024,
                Duration.ofSeconds(2),
                stderr::add
        );

        PiProcess process = new PiProcess(processOptions);
        PiRpcConnection client = new PiRpcConnection(process, Duration.ofSeconds(2), "stdio-test");
        try {
            client.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var response = client.request("get_state").toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            assertEquals("a\u2028b\u2029c", response.data().path("value").textValue());
            awaitStderr(stderr);
            assertTrue(stderr.contains("handled get_state"));
        } finally {
            client.close();
        }
        process.exit().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? name + ".exe"
                : name;
    }

    private static void awaitStderr(List<String> stderr) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (stderr.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
