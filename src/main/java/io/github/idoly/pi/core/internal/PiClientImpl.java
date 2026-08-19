package io.github.idoly.pi.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.idoly.pi.core.PiClient;
import io.github.idoly.pi.core.PiClientState;
import io.github.idoly.pi.core.PiException;
import io.github.idoly.pi.core.PiPrompt;
import io.github.idoly.pi.core.PiResult;
import io.github.idoly.pi.core.PiActivity;
import io.github.idoly.pi.core.PiActivityTimeoutException;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class PiClientImpl implements PiClient {
    private static final Set<String> ACTIVITY_COMMANDS =
            Set.of("prompt", "steer", "follow_up", "abort");

    private final PiRpcConnection connection;
    private final PiPublisher<PiEvent> events;
    private final PiToolBridge toolBridge;
    private final AtomicReference<PiClientState> state = new AtomicReference<>(PiClientState.STARTING);
    private final AtomicReference<Activity> active = new AtomicReference<>();

    PiClientImpl(PiRpcConnection connection, PiToolBridge toolBridge) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.toolBridge = toolBridge;
        this.events = new PiPublisher<>(PiThreads.executor(), 1024);
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
            throw new IllegalStateException("Prompt requires IDLE client, current state is " + state.get());
        }

        String requestId = connection.nextRequestId();
        Activity activity = new Activity(requestId, prompt.timeout());
        if (!active.compareAndSet(null, activity)) {
            state.set(PiClientState.FAILED);
            throw new IllegalStateException("Client has an inconsistent active activity");
        }

        PiCommand command = PiCommand.prompt(
                requestId, prompt.message(), prompt.images());
        activity.scheduleTimeout();
        connection.request(command).whenComplete(activity::onAcceptance);
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
        PiClientState previous = state.getAndSet(PiClientState.STOPPING);
        if (previous == PiClientState.TERMINATED || previous == PiClientState.STOPPING) {
            return;
        }
        Activity activity = active.getAndSet(null);
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

    private void onInbound(Object message) {
        if (!(message instanceof PiEvent event)) {
            return;
        }
        events.publish(event);
        Activity activity = active.get();
        if (activity != null) {
            activity.onEvent(event);
        }
    }

    private void failClient(Throwable failure) {
        state.set(PiClientState.FAILED);
        Activity activity = active.getAndSet(null);
        if (activity != null) {
            activity.fail(failure);
        }
        events.fail(failure);
    }

    private CompletionStage<Void> sendAbort() {
        if (state.get() != PiClientState.ACTIVE) {
            return CompletableFuture.completedFuture(null);
        }
        return connection.request("abort").thenApply(ignored -> null);
    }

    private final class Activity implements PiActivity {
        private final String requestId;
        private final Duration timeout;
        private final PiBufferedPublisher<PiEvent> events;
        private final CompletableFuture<PiResponse> acceptance = new CompletableFuture<>();
        private final CompletableFuture<PiResult> completion = new CompletableFuture<>();
        private final List<JsonNode> messages = new ArrayList<>();
        private final StringBuilder streamed = new StringBuilder();
        private String text = "";
        private ScheduledFuture<?> timeoutTask;
        private boolean settled;
        private boolean aborted;

        private Activity(String requestId, Duration timeout) {
            this.requestId = requestId;
            this.timeout = timeout;
            this.events = new PiBufferedPublisher<>(PiThreads.executor(), 1024);
        }

        @Override
        public String requestId() {
            return requestId;
        }

        @Override
        public Flow.Publisher<PiEvent> events() {
            return events;
        }

        @Override
        public CompletionStage<PiResponse> accepted() {
            return acceptance;
        }

        @Override
        public CompletionStage<PiResult> completion() {
            return completion;
        }

        @Override
        public CompletionStage<PiResponse> steer(String message) {
            requireActive("steer");
            return connection.request(PiCommand.steer(connection.nextRequestId(), message));
        }

        @Override
        public CompletionStage<PiResponse> followUp(String message) {
            requireActive("follow_up");
            return connection.request(PiCommand.followUp(connection.nextRequestId(), message));
        }

        @Override
        public CompletionStage<Void> abort() {
            requireActive("abort");
            return sendAbort();
        }

        private void requireActive(String operation) {
            if (active.get() != this || state.get() != PiClientState.ACTIVE) {
                throw new IllegalStateException(operation + " requires an active activity");
            }
        }

        private synchronized void onAcceptance(PiResponse response, Throwable failure) {
            if (failure != null) {
                acceptance.completeExceptionally(failure);
                if (active.compareAndSet(this, null)) {
                    state.compareAndSet(PiClientState.ACTIVE, PiClientState.IDLE);
                }
                fail(failure);
                return;
            }
            acceptance.complete(response);
            completeIfSettled();
        }

        private synchronized void onEvent(PiEvent event) {
            events.publish(event);
            event.textDelta().ifPresent(streamed::append);
            if ("message_start".equals(event.type())
                    && "assistant".equals(event.raw().path("message").path("role").asText())) {
                streamed.setLength(0);
            }
            if ("message_end".equals(event.type())) {
                JsonNode message = event.raw().get("message");
                if (message != null && message.isObject()) {
                    messages.add(message.deepCopy());
                    if ("assistant".equals(message.path("role").asText())) {
                        text = assistantText(message, streamed.toString());
                        aborted |= "aborted".equals(message.path("stopReason").asText());
                    }
                }
            }
            if ("agent_settled".equals(event.type())) {
                settled = true;
                completeIfSettled();
            }
        }

        private synchronized void completeIfSettled() {
            if (!settled || !acceptance.isDone() || acceptance.isCompletedExceptionally()
                    || completion.isDone()) {
                return;
            }
            PiResult activityResult = new PiResult(text, messages, aborted);
            cancelTimeout();
            active.compareAndSet(this, null);
            state.compareAndSet(PiClientState.ACTIVE, PiClientState.IDLE);
            events.complete();
            completion.complete(activityResult);
        }

        private synchronized void fail(Throwable failure) {
            cancelTimeout();
            completion.completeExceptionally(failure);
            events.fail(failure);
        }

        private void scheduleTimeout() {
            timeoutTask = PiThreads.scheduler().schedule(() -> {
                synchronized (this) {
                    if (completion.isDone()) {
                        return;
                    }
                    sendAbort();
                    active.compareAndSet(this, null);
                    state.compareAndSet(PiClientState.ACTIVE, PiClientState.FAILED);
                    fail(new PiActivityTimeoutException(requestId));
                }
            }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void cancelTimeout() {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
        }
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
            Activity activity = active.get();
            if (activity != null && !activity.completion.isDone()) {
                failClient(new PiException("RPC stream completed before agent_settled"));
            }
        }
    }

    private static String assistantText(JsonNode assistantMessage, String streamedFallback) {
        JsonNode content = assistantMessage.get("content");
        if (content == null) {
            return streamedFallback;
        }
        if (content.isTextual()) {
            return content.textValue();
        }
        if (!content.isArray()) {
            return streamedFallback;
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
                text.append(block.path("text").textValue());
            }
        }
        return text.isEmpty() ? streamedFallback : text.toString();
    }
}
