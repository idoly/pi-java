package io.github.idoly.pi.core.internal;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class PiScriptedTransport implements PiTransport {
    private final SubmissionPublisher<Object> inbound = new SubmissionPublisher<>();
    private final BlockingQueue<PiCommand> outbound = new LinkedBlockingQueue<>();
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final AtomicBoolean running = new AtomicBoolean();

    @Override
    public CompletionStage<Void> start() {
        running.set(true);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> send(PiCommand command) {
        if (!running.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Transport is not running"));
        }
        outbound.add(command);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Flow.Publisher<Object> inbound() {
        return inbound;
    }

    @Override
    public CompletionStage<Integer> exit() {
        return exit;
    }

    PiCommand awaitCommand(Duration timeout) throws InterruptedException {
        PiCommand command = outbound.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (command == null) {
            throw new AssertionError("Timed out waiting for outbound RPC command");
        }
        return command;
    }

    void emitInbound(Object message) {
        inbound.submit(message);
    }

    void fail(Throwable failure) {
        running.set(false);
        inbound.closeExceptionally(failure);
        exit.completeExceptionally(failure);
    }

    void exitProcess(int exitCode) {
        running.set(false);
        inbound.close();
        exit.complete(exitCode);
    }

    @Override
    public void close() {
        if (running.getAndSet(false)) {
            inbound.close();
            exit.complete(0);
        }
    }
}
