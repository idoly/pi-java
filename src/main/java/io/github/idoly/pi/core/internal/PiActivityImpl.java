package io.github.idoly.pi.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.idoly.pi.core.PiActivity;
import io.github.idoly.pi.core.PiActivityTimeoutException;
import io.github.idoly.pi.core.PiResult;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiImage;
import io.github.idoly.pi.protocol.PiResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class PiActivityImpl implements PiActivity {
    private final String requestId;
    private final Duration timeout;
    private final PiConnection connection;
    private final PiClientImpl owner;
    private final PiBufferedPublisher<PiEvent> events;
    private final CompletableFuture<PiResponse> acceptance = new CompletableFuture<>();
    private final CompletableFuture<PiResult> completion = new CompletableFuture<>();
    private final List<JsonNode> messages = new ArrayList<>();
    private final StringBuilder streamed = new StringBuilder();
    private String text = "";
    private ScheduledFuture<?> timeoutTask;
    private boolean settled;
    private boolean aborted;

    PiActivityImpl(
            String requestId,
            Duration timeout,
            PiConnection connection,
            PiClientImpl owner
    ) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.owner = Objects.requireNonNull(owner, "owner");
        events = new PiBufferedPublisher<>(PiThreads.executor(), 1024);
    }

    void start(PiCommand promptCommand) {
        scheduleTimeout();
        connection.request(promptCommand).whenComplete(this::onAcceptance);
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
    public CompletionStage<PiResponse> steer(String message, List<PiImage> images) {
        requireActive("steer");
        return connection.request(PiCommand.steer(
                connection.nextRequestId(), message, images));
    }

    @Override
    public CompletionStage<PiResponse> followUp(String message, List<PiImage> images) {
        requireActive("follow_up");
        return connection.request(PiCommand.followUp(
                connection.nextRequestId(), message, images));
    }

    @Override
    public CompletionStage<Void> abort() {
        requireActive("abort");
        return connection.request("abort").thenApply(ignored -> null);
    }

    synchronized void onEvent(PiEvent event) {
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

    synchronized void fail(Throwable failure) {
        cancelTimeout();
        acceptance.completeExceptionally(failure);
        completion.completeExceptionally(failure);
        events.fail(failure);
    }

    private void requireActive(String operation) {
        if (!owner.isActive(this)) {
            throw new IllegalStateException(operation + " requires an active activity");
        }
    }

    private synchronized void onAcceptance(PiResponse response, Throwable failure) {
        if (failure != null) {
            acceptance.completeExceptionally(failure);
            owner.release(this);
            fail(failure);
            return;
        }
        acceptance.complete(response);
        completeIfSettled();
    }

    private synchronized void completeIfSettled() {
        if (!settled || !acceptance.isDone() || acceptance.isCompletedExceptionally()
                || completion.isDone()) {
            return;
        }
        PiResult activityResult = new PiResult(text, messages, aborted);
        cancelTimeout();
        owner.release(this);
        events.complete();
        completion.complete(activityResult);
    }

    private void scheduleTimeout() {
        timeoutTask = PiThreads.scheduler().schedule(() -> {
            synchronized (this) {
                if (completion.isDone()) {
                    return;
                }
                if (owner.isActive(this)) {
                    connection.request("abort");
                }
                owner.fail(this);
                fail(new PiActivityTimeoutException(requestId));
            }
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
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
