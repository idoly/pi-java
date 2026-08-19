package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiProcessExitException;
import io.github.idoly.pi.core.PiRequestRejectedException;
import io.github.idoly.pi.core.PiRequestTimeoutException;
import io.github.idoly.pi.protocol.PiResponse;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class PiRpcConnection implements AutoCloseable {
    private final PiTransport transport;
    private final Duration timeout;
    private final String prefix;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final PiPublisher<Object> inbound;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    PiRpcConnection(PiTransport transport, Duration timeout, String prefix) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.timeout = requirePositive(timeout, "timeout");
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        this.prefix = prefix;
        this.inbound = new PiPublisher<>(PiThreads.executor(), 1024);
    }

    CompletionStage<Void> start() {
        if (!started.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Client can only be started once"));
        }
        transport.inbound().subscribe(new InboundSubscriber());
        transport.exit().whenComplete(this::onExit);
        return transport.start();
    }

    String nextRequestId() {
        return "pj-" + prefix + "-" + sequence.incrementAndGet();
    }

    CompletionStage<PiResponse> request(String command) {
        return request(PiCommand.simple(nextRequestId(), command), timeout);
    }

    CompletionStage<PiResponse> request(String command, Map<String, ?> arguments) {
        return request(
                new PiCommand(nextRequestId(), command, arguments),
                timeout
        );
    }

    CompletionStage<PiResponse> request(PiCommand command) {
        return request(command, timeout);
    }

    CompletionStage<PiResponse> request(PiCommand command, Duration timeout) {
        Objects.requireNonNull(command, "command");
        requirePositive(timeout, "timeout");
        if (!started.get() || closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Client is not running"));
        }

        CompletableFuture<PiResponse> future = new CompletableFuture<>();
        PendingRequest request = new PendingRequest(command.type(), future);
        if (pending.putIfAbsent(command.id(), request) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Duplicate request id: " + command.id()));
        }

        ScheduledFuture<?> timeoutTask = PiThreads.scheduler().schedule(
                () -> expire(command.id(), request),
                timeout.toNanos(),
                TimeUnit.NANOSECONDS);
        request.timeoutTask = timeoutTask;
        transport.send(command).whenComplete((ignored, failure) -> {
            if (failure != null && pending.remove(command.id(), request)) {
                timeoutTask.cancel(false);
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    Flow.Publisher<Object> inbound() {
        return inbound;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failPending(new PiTransportException("RPC client closed"));
        transport.close();
        inbound.complete();
    }

    private void onInbound(Object message) {
        if (message instanceof PiResponse response && response.requestId() != null) {
            PendingRequest request = pending.remove(response.requestId());
            if (request != null) {
                request.timeoutTask.cancel(false);
                if (!request.command.equals(response.command())) {
                    request.future.completeExceptionally(new PiTransportException(
                            "Response command mismatch for " + response.requestId()
                                    + ": expected " + request.command
                                    + ", got " + response.command()));
                } else if (!response.success()) {
                    request.future.completeExceptionally(
                            new PiRequestRejectedException(response));
                } else {
                    request.future.complete(response);
                }
                return;
            }
        }

        inbound.publish(message);
    }

    private void expire(String requestId, PendingRequest request) {
        if (pending.remove(requestId, request)) {
            request.future.completeExceptionally(
                    new PiRequestTimeoutException(requestId, request.command));
        }
    }

    private void onExit(Integer exitCode, Throwable failure) {
        if (failure != null) {
            failPending(failure);
            inbound.fail(failure);
        } else if (!closed.get()) {
            PiProcessExitException exitFailure =
                    new PiProcessExitException(exitCode);
            failPending(exitFailure);
            inbound.fail(exitFailure);
        }
    }

    private void failPending(Throwable failure) {
        pending.forEach((id, request) -> {
            if (pending.remove(id, request)) {
                if (request.timeoutTask != null) {
                    request.timeoutTask.cancel(false);
                }
                request.future.completeExceptionally(failure);
            }
        });
    }

    private static Duration requirePositive(Duration duration, String fieldName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return duration;
    }

    private final class InboundSubscriber implements Flow.Subscriber<Object> {
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Object message) {
            onInbound(message);
        }

        @Override
        public void onError(Throwable failure) {
            failPending(failure);
            inbound.fail(failure);
        }

        @Override
        public void onComplete() {
            // Process exit is authoritative; it distinguishes requested and unexpected closure.
        }
    }

    private static final class PendingRequest {
        private final String command;
        private final CompletableFuture<PiResponse> future;
        private volatile ScheduledFuture<?> timeoutTask;

        private PendingRequest(String command, CompletableFuture<PiResponse> future) {
            this.command = command;
            this.future = future;
        }
    }
}
