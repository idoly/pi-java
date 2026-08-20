package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class PiProcess implements PiTransport {
    private enum State { NEW, RUNNING, CLOSING, TERMINATED, FAILED }

    private final PiProcessConfig options;
    private final PiJsonCodec codec;
    private final ArrayBlockingQueue<PendingWrite> writes;
    private final PiPublisher<Object> inbound;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private volatile Process process;
    private volatile Future<?> writerTask;
    private volatile Future<?> stdoutTask;
    private volatile Future<?> stderrTask;

    PiProcess(PiProcessConfig options) {
        this.options = Objects.requireNonNull(options, "options");
        this.codec = new PiJsonCodec();
        this.writes = new ArrayBlockingQueue<>(options.commandCapacity());
        this.inbound = new PiPublisher<>(PiThreads.executor(), options.subscriberCapacity());
    }

    @Override
    public CompletionStage<Void> start() {
        if (!state.compareAndSet(State.NEW, State.RUNNING)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("pi process can only be started once"));
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(options.command())
                    .directory(options.directory().toFile());
            builder.environment().putAll(options.env());
            process = builder.start();

            writerTask = PiThreads.executor().submit(this::writeLoop);
            stdoutTask = PiThreads.executor().submit(this::readStdout);
            stderrTask = PiThreads.executor().submit(this::readStderr);
            process.onExit().whenComplete(this::onExit);
            return CompletableFuture.completedFuture(null);
        } catch (Exception failure) {
            state.set(State.FAILED);
            cancelTasks();
            Process current = process;
            if (current != null && current.isAlive()) {
                current.destroyForcibly();
            }
            PiException startFailure =
                    new PiException("Failed to start pi process", failure);
            inbound.fail(startFailure);
            exit.completeExceptionally(startFailure);
            return CompletableFuture.failedFuture(startFailure);
        }
    }

    @Override
    public CompletionStage<Void> send(PiCommand command) {
        Objects.requireNonNull(command, "command");
        if (state.get() != State.RUNNING) {
            return CompletableFuture.failedFuture(
                    new PiException("pi process is not running: " + state.get()));
        }
        PendingWrite write = new PendingWrite(command, new CompletableFuture<>());
        if (!writes.offer(write)) {
            write.completion().completeExceptionally(
                    new PiException("RPC command queue is full"));
        }
        return write.completion();
    }

    @Override
    public Flow.Publisher<Object> inbound() {
        return inbound;
    }

    @Override
    public CompletionStage<Integer> exit() {
        return exit;
    }

    @Override
    public void close() {
        State previousState = state.getAndUpdate(currentState ->
                currentState == State.NEW || currentState == State.RUNNING
                        ? State.CLOSING : currentState);
        if (previousState == State.CLOSING
                || previousState == State.TERMINATED
                || previousState == State.FAILED) {
            return;
        }
        failWrites(new PiException("pi process closed"));

        Process current = process;
        cancelTasks();
        if (current == null) {
            state.set(State.TERMINATED);
            inbound.complete();
            exit.complete(-1);
            return;
        }

        closeQuietly(current.getOutputStream());
        if (current.isAlive()) {
            current.destroy();
            Duration gracePeriod = options.gracePeriod();
            try {
                if (!current.waitFor(
                        gracePeriod.toMillis(), TimeUnit.MILLISECONDS)) {
                    current.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
    }

    private void writeLoop() {
        try (OutputStream output = process.getOutputStream()) {
            while (!Thread.currentThread().isInterrupted()) {
                PendingWrite write = writes.take();
                try {
                    output.write(codec.encode(write.command()));
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

    private void readStdout() {
        PiJsonlFramer framer = new PiJsonlFramer(options.maxLineBytes());
        byte[] buffer = new byte[8192];
        try (InputStream stdout = process.getInputStream()) {
            int bytesRead;
            while ((bytesRead = stdout.read(buffer)) != -1) {
                for (byte[] line : framer.accept(buffer, 0, bytesRead)) {
                    publish(line);
                }
            }
            byte[] finalLine = framer.finish();
            if (finalLine != null) {
                publish(finalLine);
            }
        } catch (Throwable failure) {
            if (state.get() == State.RUNNING) {
                fail(new PiException("RPC stdout reader failed", failure));
            }
        }
    }

    private void publish(byte[] line) {
        if (line.length != 0) {
            inbound.publish(codec.decode(line));
        }
    }

    private void readStderr() {
        try (BufferedReader reader = process.errorReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                options.stderr().accept(line);
            }
        } catch (Throwable failure) {
            if (state.get() == State.RUNNING) {
                fail(new PiException("RPC stderr reader failed", failure));
            }
        }
    }

    private void onExit(Process exited, Throwable failure) {
        if (failure != null) {
            fail(new PiException("Failed to observe pi process exit", failure));
            return;
        }
        boolean requested = state.get() == State.CLOSING;
        state.set(State.TERMINATED);
        int exitCode = exited.exitValue();
        exit.complete(exitCode);
        if (requested || exitCode == 0) {
            inbound.complete();
        } else {
            inbound.fail(new PiException(
                    "pi process exited unexpectedly with code " + exitCode));
        }
        failWrites(new PiException("pi process exited with code " + exitCode));
    }

    private void fail(PiException failure) {
        state.set(State.FAILED);
        inbound.fail(failure);
        failWrites(failure);
        exit.completeExceptionally(failure);
        cancelTasks();
        Process current = process;
        if (current != null && current.isAlive()) {
            current.destroyForcibly();
        }
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
        PendingWrite write;
        while ((write = writes.poll()) != null) {
            write.completion().completeExceptionally(failure);
        }
    }

    private static void closeQuietly(OutputStream output) {
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }

    private record PendingWrite(
            PiCommand command,
            CompletableFuture<Void> completion
    ) {
    }
}
