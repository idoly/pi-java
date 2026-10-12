package io.github.idoly.pi.core;

import io.github.idoly.pi.ProcessExitException;
import io.github.idoly.pi.RequestRejectedException;
import io.github.idoly.pi.RequestTimeoutException;
import io.github.idoly.pi.protocol.PiImage;
import io.github.idoly.pi.protocol.PiResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Request correlation over one PiProcess. Internal response timeouts use milliseconds. */
final class PiConnection implements AutoCloseable {
    private enum State { NEW, RUNNING, FAILED, CLOSED }

    record Request(String id, String type, Map<String, ?> arguments) {
        Request {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Request id must not be blank");
            if (type == null || type.isBlank()) throw new IllegalArgumentException("Request type must not be blank");
            arguments = Map.copyOf(arguments);
            if (arguments.containsKey("id") || arguments.containsKey("type")) {
                throw new IllegalArgumentException("RPC arguments must not override id or type");
            }
        }
        static Request message(String id, String type, String text, List<PiImage> images) {
            Objects.requireNonNull(text, "message");
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("message", text);
            if (images != null && !images.isEmpty()) arguments.put("images", images.stream()
                    .map(image -> Map.of("type", "image", "data", image.data(), "mimeType", image.mimeType())).toList());
            return new Request(id, type, arguments);
        }
        static Request simple(String id, String type) { return new Request(id, type, Map.of()); }
    }

    private final PiProcess process;
    private final long timeoutMillis;
    private final String prefix;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, CompletableFuture<PiResponse>> pending = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private volatile State state = State.NEW;
    private Consumer<Object> listener = ignored -> {};
    private Consumer<Throwable> failureListener = ignored -> {};
    private Consumer<String> diagnostics = ignored -> {};

    PiConnection(PiProcess process, long timeoutMillis, String prefix) {
        this.process = Objects.requireNonNull(process, "process");
        this.timeoutMillis = timeoutMillis;
        this.prefix = prefix;
    }

    void diagnostics(Consumer<String> diagnostics) { this.diagnostics = diagnostics; }
    void listen(Consumer<Object> listener, Consumer<Throwable> failureListener) {
        synchronized (lifecycle) {
            if (state != State.NEW) throw new IllegalStateException("Listener must be installed before start");
            this.listener = Objects.requireNonNull(listener, "listener");
            this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
        }
    }
    CompletionStage<Void> start() {
        synchronized (lifecycle) {
            if (state != State.NEW) {
                return CompletableFuture.failedFuture(new IllegalStateException("Connection can only be started once"));
            }
            state = State.RUNNING;
        }
        process.termination().whenComplete((code, failure) -> {
            if (state == State.CLOSED) return;
            Throwable reason = failure == null ? new ProcessExitException(code) : PiSupport.unwrap(failure);
            failPending(reason);
            failureListener.accept(reason);
        });
        return process.start(this::onRecord);
    }
    String nextRequestId() { return "pj-" + prefix + "-" + sequence.incrementAndGet(); }
    CompletionStage<PiResponse> request(String command) { return request(Request.simple(nextRequestId(), command)); }
    CompletionStage<PiResponse> request(String command, Map<String, ?> arguments) {
        return request(new Request(nextRequestId(), command, arguments));
    }
    CompletionStage<PiResponse> request(String command, Map<String, ?> arguments, long timeoutMillis) {
        return request(new Request(nextRequestId(), command, arguments), timeoutMillis);
    }
    CompletionStage<PiResponse> request(Request request) { return request(request, timeoutMillis); }

    CompletionStage<PiResponse> request(Request request, long timeoutMillis) {
        PiSupport.positive(timeoutMillis, "Response timeout milliseconds");
        synchronized (lifecycle) {
            if (!running()) return CompletableFuture.failedFuture(new IllegalStateException("Client is not running"));
            CompletableFuture<PiResponse> response = new CompletableFuture<>();
            if (pending.putIfAbsent(request.id(), response) != null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Duplicate request id: " + request.id()));
            }
            ScheduledFuture<?> deadline = PiThreads.scheduler().schedule(() -> {
                if (pending.remove(request.id(), response)) {
                    diagnostics.accept("[timeout] id=" + request.id() + " command=" + request.type());
                    response.completeExceptionally(new RequestTimeoutException(request.id(), request.type()));
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            CompletionStage<PiResponse> result = response.whenComplete((value, failure) -> {
                deadline.cancel(false);
                pending.remove(request.id(), response);
            }).thenApply(value -> {
                if (!request.type().equals(value.command())) throw new PiException(
                        "Response command mismatch for " + request.id() + ": expected " + request.type() + ", got " + value.command());
                if (!value.success()) throw new RequestRejectedException(value);
                return value;
            });
            diagnostics.accept("[request] id=" + request.id() + " command=" + request.type() + " timeoutMillis=" + timeoutMillis);
            try {
                process.send(request).whenComplete((ignored, failure) -> {
                    if (failure != null && pending.remove(request.id(), response)) response.completeExceptionally(failure);
                });
            } catch (RuntimeException failure) {
                response.completeExceptionally(failure);
            }
            return result;
        }
    }

    CompletionStage<Void> sendUi(String id, Map<String, ?> values) {
        synchronized (lifecycle) {
            if (!running()) return CompletableFuture.failedFuture(new IllegalStateException("Client is not running"));
            return process.send(new Request(id, "extension_ui_response", values));
        }
    }
    private boolean running() { return state == State.RUNNING; }

    @Override public void close() {
        boolean first;
        synchronized (lifecycle) {
            first = state != State.CLOSED;
            state = State.CLOSED;
        }
        try {
            if (first) failPending(new PiException("RPC client closed"));
        } finally {
            // Repeated callers wait for the process's same cleanup, without holding this lock.
            process.close();
        }
    }
    private void onRecord(Object message) {
        if (message instanceof PiResponse response && response.requestId() != null) {
            CompletableFuture<PiResponse> request = pending.remove(response.requestId());
            if (request != null) {
                diagnostics.accept("[response] id=" + response.requestId() + " command=" + response.command() + " success=" + response.success());
                request.complete(response);
                return;
            }
        }
        listener.accept(message);
    }
    private void failPending(Throwable failure) {
        List<CompletableFuture<PiResponse>> requests;
        synchronized (lifecycle) {
            if (state != State.CLOSED) state = State.FAILED;
            requests = new ArrayList<>(pending.values());
            pending.clear();
        }
        requests.forEach(request -> request.completeExceptionally(failure));
    }
}
