package io.github.idoly.pi.core;

import io.github.idoly.pi.ProcessExitException;
import io.github.idoly.pi.core.PiConnection.Request;
import io.github.idoly.pi.protocol.PiEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiProcessTest {
    @Test
    void communicatesWithRealChildProcessAndDrainsStderr() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        List<String> stderr = new CopyOnWriteArrayList<>();
        PiProcess.Config processOptions = new PiProcess.Config(
                List.of(
                        java.toString(),
                        "-cp", System.getProperty("java.class.path"),
                        PiProcessStub.class.getName()
                ),
                Path.of(System.getProperty("user.dir")),
                Map.of(),
                8,
                1024 * 1024,
                2000,
                stderr::add
        );

        PiProcess process = new PiProcess(processOptions);
        PiConnection client = new PiConnection(process, 2000, "stdio-test");
        try {
            client.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var response = client.request("get_state").toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            assertEquals("a\u2028b\u2029c", response.data().path("value").textValue());
            awaitStderr(stderr);
            assertTrue(stderr.stream().anyMatch(line -> line.equals("[stderr] handled get_state")));
        } finally {
            client.close();
        }
        process.exit().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void drainsLastResponseBeforeUnexpectedExitFailsRemainingRequests() throws Exception {
        PiProcess process = childProcess(1000, "last-response");
        PiConnection connection = new PiConnection(process, 5000, "last");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        connection.listen(message -> {
            if (message instanceof PiEvent event && event.type().equals("pause")) {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            }
        }, ignored -> {});
        try {
            connection.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var response = connection.request("get_state").toCompletableFuture();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var remaining = connection.request("get_messages").toCompletableFuture();
            assertEquals(0, process.exit().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertFalse(response.isDone(), "Exit must not overtake the queued response");
            assertFalse(remaining.isDone());
            release.countDown();
            assertTrue(response.get(2, TimeUnit.SECONDS).success());
            ExecutionException failure = assertThrows(ExecutionException.class, () -> remaining.get(2, TimeUnit.SECONDS));
            assertInstanceOf(ProcessExitException.class, failure.getCause());
        } finally { release.countDown(); connection.close(); }
    }

    @Test
    void readerFailureAndCloseReapOwnedChild(@TempDir Path directory) throws Exception {
        Path pidFile = directory.resolve("child.pid");
        PiProcess process = childProcess(200, "bad-tree", pidFile.toString());
        ProcessHandle child = null;
        try {
            process.start(ignored -> {}).toCompletableFuture().get(2, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> process.exit().toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertInstanceOf(PiException.class, failure.getCause());
            child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElse(null);
            process.close();
            assertTrue(child == null || !child.isAlive(), "Failed process cleanup must include its child");
        } finally {
            process.close();
            if (child == null && Files.exists(pidFile)) child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElse(null);
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.onExit().get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void closeIsBoundedWhenChildDoesNotReadStdin() throws Exception {
        PiProcess process = childProcess(200, "sleep");
        CompletableFuture<Void> ready = new CompletableFuture<>();
        try {
            process.start(value -> ready.complete(null)).toCompletableFuture().get(2, TimeUnit.SECONDS);
            ready.get(2, TimeUnit.SECONDS);
            var write = process.send(Request.message("large", "prompt", "x".repeat(1024 * 1024), List.of())).toCompletableFuture();
            assertThrows(TimeoutException.class, () -> write.get(100, TimeUnit.MILLISECONDS));
            CompletableFuture.runAsync(process::close).get(2, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> write.get(1, TimeUnit.SECONDS));
        } finally { process.close(); }
    }

    @Test
    void truncatesHugeStderrLinesWithoutBlockingRpc() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", executable("java")).toString(),
                "-cp", System.getProperty("java.class.path"), PiProcessStub.class.getName(), "huge-stderr");
        PiProcess process = new PiProcess(new PiProcess.Config(command, Path.of(System.getProperty("user.dir")), Map.of(),
                8, 1024 * 1024, 1000, lines::add));
        try (PiConnection connection = new PiConnection(process, 2000, "stderr")) {
            connection.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(connection.request("get_state").toCompletableFuture().get(2, TimeUnit.SECONDS).success());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (lines.stream().noneMatch(line -> line.contains("[truncated]")) && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(lines.stream().anyMatch(line -> line.length() < 2200 && line.endsWith("[truncated]")));
        }
    }

    private static PiProcess childProcess(long graceMillis, String... arguments) {
        ArrayList<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable("java")).toString(),
                "-cp", System.getProperty("java.class.path"), PiProcessStub.class.getName()));
        command.addAll(List.of(arguments));
        return new PiProcess(new PiProcess.Config(command, Path.of(System.getProperty("user.dir")), Map.of(),
                8, 1024 * 1024, graceMillis, null));
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? name + ".exe"
                : name;
    }

    private static void awaitStderr(List<String> stderr) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (stderr.stream().noneMatch(line -> line.equals("[stderr] handled get_state")) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
