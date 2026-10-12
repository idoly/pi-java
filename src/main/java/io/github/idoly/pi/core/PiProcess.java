package io.github.idoly.pi.core;

import io.github.idoly.pi.core.PiConnection.Request;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class PiProcess implements AutoCloseable {
    private static final int DIAGNOSTIC_RECORD_LIMIT = 64;
    private static final int DIAGNOSTIC_BYTE_LIMIT = 128 * 1024;
    private static final int DIAGNOSTIC_TEXT_LIMIT = 4096;
    private static final int STDERR_LINE_LIMIT = 2048;
    private static final int STDERR_TAIL_LIMIT = 8;

    private enum State {
        NEW,
        RUNNING,
        CLOSING,
        TERMINATED,
        FAILED
    }

    private final Config options;
    private final ArrayBlockingQueue<Write> writes;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final CompletableFuture<Void> stdoutDone = new CompletableFuture<>();
    private final CompletableFuture<Void> cleanupDone = new CompletableFuture<>();
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();
    private boolean exitReported;
    private volatile Thread cleanupThread;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final PiPublisher<String> diagnosticEvents;
    private final ArrayDeque<String> stderrTail = new ArrayDeque<>();
    private volatile Process process;
    private volatile Future<?> writerTask;
    private volatile Future<?> stdoutTask;
    private volatile Future<?> stderrTask;

    PiProcess(Config options) {
        this.options = Objects.requireNonNull(options, "options");
        this.writes = new ArrayBlockingQueue<>(options.commandCapacity());
        diagnosticEvents = new PiPublisher<>(PiThreads.executor(), DIAGNOSTIC_RECORD_LIMIT, DIAGNOSTIC_BYTE_LIMIT,
                line -> line.getBytes(StandardCharsets.UTF_8).length, ignored -> {});
        if (options.diagnostics() != null) diagnosticEvents.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(String line) { options.diagnostics().accept(line); }
            public void onError(Throwable failure) { /* Drop diagnostics rather than block protocol work. */ }
            public void onComplete() {}
        });
    }

    void diagnostic(String line) {
        diagnosticEvents.publish(line.length() > DIAGNOSTIC_TEXT_LIMIT ? line.substring(0, DIAGNOSTIC_TEXT_LIMIT) : line);
    }

    synchronized CompletionStage<Void> start(Consumer<Object> receiver) {
        if (!state.compareAndSet(State.NEW, State.RUNNING)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("pi process can only be started once"));
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(options.command())
                    .directory(options.directory().toFile());
            builder.environment().putAll(options.env());
            process = builder.start();

            diagnostic("[process-start] pid=" + process.pid());
            writerTask = PiThreads.executor().submit(this::writeLoop);
            stdoutTask = PiThreads.executor().submit(() -> readStdout(receiver));
            stderrTask = PiThreads.executor().submit(this::readStderr);
            process.onExit().whenComplete(this::onExit);
            return CompletableFuture.completedFuture(null);
        } catch (Exception failure) {
            PiException startFailure = new PiException("Failed to start pi process", failure);
            fail(startFailure);
            return CompletableFuture.failedFuture(startFailure);
        }
    }

    synchronized CompletionStage<Void> send(Request request) {
        Objects.requireNonNull(request, "request");
        if (state.get() != State.RUNNING) {
            return CompletableFuture.failedFuture(
                    new PiException("pi process is not running: " + state.get()));
        }
        Write write = new Write(request, new CompletableFuture<>());
        if (!writes.offer(write)) {
            write.completion().completeExceptionally(
                    new PiException("RPC command queue is full"));
        }
        return write.completion();
    }

    /** Completes after physical exit and stdout processing; failures are propagated immediately. */
    CompletionStage<Integer> termination() {
        return exit.thenCompose(code -> stdoutDone.thenApply(ignored -> code));
    }

    CompletionStage<Integer> exit() {
        return exit;
    }

    @Override
    public void close() {
        Process current = null;
        Map<Long, ProcessHandle> children = new LinkedHashMap<>();
        State previous = null;
        boolean ownsCleanup;
        synchronized (this) {
            ownsCleanup = cleanupStarted.compareAndSet(false, true);
            if (ownsCleanup) {
                cleanupThread = Thread.currentThread();
                previous = state.getAndUpdate(value -> value == State.FAILED || value == State.TERMINATED
                        ? value : State.CLOSING);
                current = process;
            }
        }
        if (!ownsCleanup) {
            if (cleanupThread != Thread.currentThread()) cleanupDone.join();
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            if (current != null) current.descendants().forEach(child -> children.put(child.pid(), child));
            failWrites(new PiException("pi process closed"));
            cancel(writerTask); // The writer closes stdin on exit; never wait for its stream lock here.
            if (current == null) {
                state.set(State.TERMINATED);
                stdoutDone.complete(null);
                exit.complete(-1);
                return;
            }
            if (previous != State.FAILED) {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.gracePeriodMillis());
                try {
                    current.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    for (ProcessHandle child : children.values()) {
                        if (child.isAlive()) {
                            try { child.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                            catch (TimeoutException | ExecutionException ignored) { }
                        }
                    }
                } catch (InterruptedException failure) { interrupted = true; }
            }
        } finally {
            try {
                if (current != null) {
                    if (current.isAlive()) current.descendants().forEach(child -> children.putIfAbsent(child.pid(), child));
                    List<ProcessHandle> handles = new ArrayList<>(children.values());
                    Collections.reverse(handles);
                    handles.add(current.toHandle());
                    for (ProcessHandle handle : handles) if (handle.isAlive()) handle.destroyForcibly();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    for (ProcessHandle handle : handles) {
                        if (handle.isAlive()) {
                            try { handle.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                            catch (InterruptedException failure) { interrupted = true; }
                            catch (ExecutionException | TimeoutException ignored) { }
                        }
                    }
                }
            } catch (RuntimeException | Error failure) {
                cleanupDone.completeExceptionally(failure);
                throw failure;
            } finally {
                cancelTasks();
                cleanupDone.complete(null);
                if (current != null && !current.isAlive()) reportExit(current.exitValue());
                diagnosticEvents.complete();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private void writeLoop() {
        try (OutputStream output = process.getOutputStream()) {
            while (!Thread.currentThread().isInterrupted()) {
                Write write = writes.take();
                try {
                    output.write(PiCodec.encode(write.request().id(), write.request().type(), write.request().arguments()));
                    output.write('\n');
                    output.flush();
                    write.completion().complete(null);
                } catch (Throwable failure) {
                    write.completion().completeExceptionally(failure);
                    throw failure;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable failure) {
            if (state.get() == State.RUNNING) {
                fail(new PiException("RPC writer failed", failure));
            }
        } finally {
            failWrites(new PiException("RPC writer stopped"));
        }
    }

    private void readStdout(Consumer<Object> receiver) {
        PiCodec codec = new PiCodec(options.maxLineBytes());
        byte[] buffer = new byte[8192];
        try (InputStream stdout = process.getInputStream()) {
            int bytesRead;
            while ((bytesRead = stdout.read(buffer)) != -1) {
                codec.decode(buffer, 0, bytesRead, receiver);
            }
            codec.finish(receiver);
        } catch (Throwable failure) {
            if (state.get() == State.RUNNING) {
                fail(new PiException("RPC stdout reader failed", failure));
            }
        } finally {
            stdoutDone.complete(null);
        }
    }

    private void readStderr() {
        try (BufferedReader reader = process.errorReader(StandardCharsets.UTF_8)) {
            char[] chunk = new char[4096];
            StringBuilder line = new StringBuilder();
            boolean truncated = false;
            int read;
            while ((read = reader.read(chunk)) != -1) {
                for (int i = 0; i < read; i++) {
                    if (chunk[i] == '\n') {
                        publishStderr(line.toString() + (truncated ? " [truncated]" : ""));
                        line.setLength(0); truncated = false;
                    } else if (line.length() < STDERR_LINE_LIMIT) line.append(chunk[i]);
                    else truncated = true;
                }
            }
            if (line.length() > 0) publishStderr(line.toString() + (truncated ? " [truncated]" : ""));
        } catch (Throwable failure) {
            if (state.get() == State.RUNNING) fail(new PiException("RPC stderr reader failed", failure));
        }
    }

    private void publishStderr(String line) {
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        synchronized (stderrTail) {
            if (stderrTail.size() == STDERR_TAIL_LIMIT) stderrTail.removeFirst();
            stderrTail.addLast(line);
        }
        diagnostic("[stderr] " + line);
        System.getLogger("io.github.idoly.pi.process").log(System.Logger.Level.DEBUG, line);
    }

    private void onExit(Process exited, Throwable failure) {
        if (failure != null) {
            fail(new PiException("Failed to observe pi process exit", failure));
            return;
        }
        state.set(State.TERMINATED);
        int exitCode = exited.exitValue();
        reportExit(exitCode);
        exit.complete(exitCode);
        failWrites(new PiException("pi process exited with code " + exitCode));
    }

    private synchronized void reportExit(int exitCode) {
        if (!exitReported) {
            synchronized (stderrTail) { diagnostic("[process-exit] code=" + exitCode + " stderrTail=" + stderrTail); }
            exitReported = true;
        }
    }

    private void fail(PiException failure) {
        state.set(State.FAILED);
        diagnostic("[process-failed] " + failure.getMessage());
        exit.completeExceptionally(failure);
        close();
    }

    private void cancelTasks() {
        cancel(writerTask);
        cancel(stdoutTask);
        cancel(stderrTask);
    }

    private static void cancel(Future<?> task) {
        if (task != null) {
            task.cancel(true);
        }
    }

    private void failWrites(Throwable failure) {
        Write write;
        while ((write = writes.poll()) != null) {
            write.completion().completeExceptionally(failure);
        }
    }

    record Config(List<String> command, Path directory, Map<String, String> env,
                  int commandCapacity, int maxLineBytes, long gracePeriodMillis, Consumer<String> diagnostics) {
        Config {
            command = List.copyOf(command);
            directory = Objects.requireNonNull(directory, "directory");
            env = Map.copyOf(env);
            if (command.isEmpty()) throw new IllegalArgumentException("command must not be empty");
            PiSupport.positive(commandCapacity, "commandCapacity");
            PiSupport.positive(maxLineBytes, "maxLineBytes");
            PiSupport.nonNegative(gracePeriodMillis, "gracePeriodMillis");
        }
    }

    private record Write(
            Request request,
            CompletableFuture<Void> completion
    ) {
    }

}
