package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiActivity;
import io.github.idoly.pi.core.PiClient;
import io.github.idoly.pi.core.PiClientState;
import io.github.idoly.pi.core.PiException;
import io.github.idoly.pi.core.PiPrompt;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiResponse;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

final class PiClientImpl implements PiClient {
    private static final Set<String> ACTIVITY_COMMANDS =
            Set.of("prompt", "steer", "follow_up", "abort");

    private final PiConnection connection;
    private final PiPublisher<PiEvent> events;
    private final PiToolBridge toolBridge;
    private final AtomicReference<PiClientState> state =
            new AtomicReference<>(PiClientState.STARTING);
    private final AtomicReference<PiActivityImpl> active = new AtomicReference<>();

    PiClientImpl(PiConnection connection, PiToolBridge toolBridge) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.toolBridge = toolBridge;
        events = new PiPublisher<>(PiThreads.executor(), 1024);
    }

    CompletionStage<Void> start() {
        connection.inbound().subscribe(new InboundSubscriber());
        return connection.start().whenComplete((ignored, failure) -> {
            if (failure == null) {
                state.compareAndSet(PiClientState.STARTING, PiClientState.IDLE);
            } else {
                state.set(PiClientState.FAILED);
                events.fail(failure);
            }
        });
    }

    @Override
    public PiActivity prompt(String message) {
        return prompt(PiPrompt.text(message));
    }

    @Override
    public PiActivity prompt(PiPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        if (!state.compareAndSet(PiClientState.IDLE, PiClientState.ACTIVE)) {
            throw new IllegalStateException(
                    "Prompt requires IDLE client, current state is " + state.get());
        }

        String requestId = connection.nextRequestId();
        PiActivityImpl activity = new PiActivityImpl(
                requestId, prompt.timeout(), connection, this);
        if (!active.compareAndSet(null, activity)) {
            state.set(PiClientState.FAILED);
            throw new IllegalStateException("Client has an inconsistent active activity");
        }

        activity.start(PiCommand.prompt(
                requestId, prompt.message(), prompt.images()));
        return activity;
    }

    @Override
    public CompletionStage<PiResponse> request(String command, Map<String, ?> arguments) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(arguments, "arguments");
        if (ACTIVITY_COMMANDS.contains(command)) {
            throw new IllegalArgumentException(command + " is managed by PiActivity");
        }
        return connection.request(command, arguments);
    }

    @Override
    public Flow.Publisher<PiEvent> events() {
        return events;
    }

    @Override
    public PiClientState state() {
        return state.get();
    }

    @Override
    public void close() {
        PiClientState previous = state.getAndUpdate(current ->
                current == PiClientState.TERMINATED || current == PiClientState.STOPPING
                        ? current : PiClientState.STOPPING);
        if (previous == PiClientState.TERMINATED || previous == PiClientState.STOPPING) {
            return;
        }
        PiActivityImpl activity = active.getAndSet(null);
        if (activity != null) {
            activity.fail(new PiException("Client closed while activity was active"));
        }
        connection.close();
        if (toolBridge != null) {
            toolBridge.close();
        }
        events.complete();
        state.set(PiClientState.TERMINATED);
    }

    boolean isActive(PiActivityImpl activity) {
        return active.get() == activity && state.get() == PiClientState.ACTIVE;
    }

    void release(PiActivityImpl activity) {
        if (active.compareAndSet(activity, null)) {
            state.compareAndSet(PiClientState.ACTIVE, PiClientState.IDLE);
        }
    }

    void fail(PiActivityImpl activity) {
        if (active.compareAndSet(activity, null)) {
            state.compareAndSet(PiClientState.ACTIVE, PiClientState.FAILED);
        }
    }

    private void onInbound(Object message) {
        if (!(message instanceof PiEvent event)) {
            return;
        }
        events.publish(event);
        PiActivityImpl activity = active.get();
        if (activity != null) {
            activity.onEvent(event);
        }
    }

    private void failClient(Throwable failure) {
        state.set(PiClientState.FAILED);
        PiActivityImpl activity = active.getAndSet(null);
        if (activity != null) {
            activity.fail(failure);
        }
        events.fail(failure);
    }

    private final class InboundSubscriber implements Flow.Subscriber<Object> {
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Object inboundMessage) {
            onInbound(inboundMessage);
        }

        @Override
        public void onError(Throwable failure) {
            failClient(failure);
        }

        @Override
        public void onComplete() {
            PiActivityImpl activity = active.get();
            if (activity != null && !activity.completion().toCompletableFuture().isDone()) {
                failClient(new PiException("RPC stream completed before agent_settled"));
            }
        }
    }
}
