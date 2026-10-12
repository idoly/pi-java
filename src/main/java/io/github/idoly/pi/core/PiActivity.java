package io.github.idoly.pi.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.idoly.pi.core.PiConnection.Request;
import io.github.idoly.pi.ActivityTimeoutException;
import io.github.idoly.pi.RequestRejectedException;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiImage;
import io.github.idoly.pi.protocol.PiResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One prompt until it is handled or its run settles. Created by PiClient. */
public final class PiActivity {
    private final String requestId;
    private final PiConnection connection;
    private final PiClient owner;
    private final PiConfig config;
    private final Consumer<String> diagnostics;
    private final PiPublisher<PiEvent> events;
    private final CompletableFuture<PiResponse> acceptance = new CompletableFuture<>();
    private final CompletableFuture<PiResult> completion = new CompletableFuture<>();
    private final List<JsonNode> messages = new ArrayList<>();
    private long messageBytes;
    private long streamedBytes;
    private String text = "";
    private String disposition = "started";
    private String stopReason;
    private String errorMessage;
    private ScheduledFuture<?> timeoutTask;
    private boolean accepted;
    private boolean settled;
    private boolean runStarted;
    private boolean aborted;
    private boolean terminal;
    private int pendingControls;
    private boolean queuedControl;
    private Throwable terminalFailure;
    private CompletionStage<Void> cancellation;

    PiActivity(String requestId, PiConnection connection, PiClient owner,
               PiConfig config, Consumer<String> diagnostics) {
        this.requestId = requestId;
        this.connection = connection;
        this.owner = owner;
        this.config = config;
        this.diagnostics = diagnostics;
        events = new PiPublisher<>(PiThreads.executor(), config.eventBufferCapacity(), config.eventBufferLimit(),
                PiCodec::recordBytes, diagnostics, true);
    }

    void start(Request command, long timeoutSeconds) {
        timeoutTask = PiThreads.scheduler().schedule(this::onTimeout, timeoutSeconds, TimeUnit.SECONDS);
        connection.request(command).whenComplete(this::onAcceptance);
    }

    public String requestId() { return requestId; }
    public Flow.Publisher<PiEvent> events() { return events; }
    public CompletionStage<PiResponse> accepted() { return acceptance.minimalCompletionStage(); }
    public CompletionStage<PiResult> completion() { return completion.minimalCompletionStage(); }
    /** Sends an extension command immediately; ordinary input uses Pi's steering queue. */
    public CompletionStage<PiResponse> prompt(String message) {
        return control(new Request(connection.nextRequestId(), "prompt",
                Map.of("message", message, "streamingBehavior", "steer")));
    }

    public CompletionStage<PiResponse> steer(String message) { return steer(message, List.of()); }
    public CompletionStage<PiResponse> steer(String message, List<PiImage> images) {
        return control(Request.message(connection.nextRequestId(), "steer", message, images));
    }
    public CompletionStage<PiResponse> followUp(String message) { return followUp(message, List.of()); }
    public CompletionStage<PiResponse> followUp(String message, List<PiImage> images) {
        return control(Request.message(connection.nextRequestId(), "follow_up", message, images));
    }

    private CompletionStage<PiResponse> control(Request command) {
        synchronized (owner.lifecycle) {
            if (cancellation != null) throw new IllegalStateException("Activity is being aborted");
            CompletionStage<PiResponse> request = owner.control(this, command);
            pendingControls++;
            return PiSupport.external(request.whenComplete((response, failure) -> {
                synchronized (owner.lifecycle) {
                    pendingControls--;
                    if (terminal) return;
                    if (failure != null) {
                        Throwable cause = PiSupport.unwrap(failure);
                        if (!(cause instanceof RequestRejectedException)) fail(cause);
                        else completeIfReady();
                        return;
                    }
                    if (!"handled".equals(dispositionOf(response))) {
                        queuedControl = true;
                        if (settled || "handled".equals(disposition)) {
                            PiException ended = new PiException(command.type() + " was queued after the activity ended; recreate the client");
                            fail(ended);
                            throw ended;
                        }
                    }
                    completeIfReady();
                }
            }));
        }
    }

    /** Clears queued input and asks Pi to abort its active run. */
    public CompletionStage<Void> abort() {
        synchronized (owner.lifecycle) {
            if (cancellation != null) return cancellation;
            if (terminal) return terminalFailure == null ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(new IllegalStateException("Activity failed; close and recreate the client", terminalFailure));
            CompletionStage<PiResponse> clear = owner.control(this, Request.simple(connection.nextRequestId(), "clear_queue"));
            pendingControls++;
            CompletionStage<Void> operation = clear.thenCompose(ignored -> {
                synchronized (owner.lifecycle) {
                    if (terminal) return terminalFailure == null ? CompletableFuture.<Void>completedFuture(null)
                            : CompletableFuture.<Void>failedFuture(terminalFailure);
                    if (settled) return CompletableFuture.<Void>completedFuture(null);
                    return owner.control(this, Request.simple(connection.nextRequestId(), "abort")).thenApply(response -> (Void) null);
                }
            }).whenComplete((ignored, failure) -> {
                synchronized (owner.lifecycle) {
                    pendingControls--;
                    if (failure != null) fail(PiSupport.unwrap(failure));
                    else completeIfReady();
                }
            });
            cancellation = PiSupport.external(operation);
            return cancellation;
        }
    }

    // Internal state transitions run under the client's lifecycle lock.
    void onEvent(PiEvent event) {
        if (terminal) return;
        events.publish(event);
        switch (event.type()) {
            case "agent_start" -> { runStarted = true; settled = false; }
            case "message_start" -> {
                if ("assistant".equals(event.raw().path("message").path("role").asText())) {
                    streamedBytes = 0;
                    if (!config.retainMessages()) text = "";
                }
            }
            case "message_update" -> {
                String delta = event.textDelta().orElse(null);
                if (delta == null) return;
                long bytes = delta.getBytes(StandardCharsets.UTF_8).length;
                if (withinBudget(messageBytes + streamedBytes + bytes)) streamedBytes += bytes;
            }
            case "message_end" -> onMessageEnd(event.raw().get("message"));
            case "agent_settled" -> {
                if (event.raw().path("aborted").isBoolean()) aborted = event.raw().path("aborted").booleanValue();
                settled = true;
                completeIfReady();
            }
            default -> { }
        }
    }

    private void onMessageEnd(JsonNode message) {
        if (message == null || !message.isObject()) return;
        boolean assistant = "assistant".equals(message.path("role").asText());
        if (config.retainMessages()) {
            long bytes = PiCodec.jsonBytes(message);
            if (!withinBudget(messageBytes + bytes + (assistant ? 0 : streamedBytes))) return;
            messageBytes += bytes;
            messages.add(message.deepCopy());
        }
        if (!assistant) return;
        String finalText = assistantText(message);
        long textBytes = config.retainMessages() ? 0 : finalText.getBytes(StandardCharsets.UTF_8).length;
        if (!withinBudget(messageBytes + textBytes)) return;
        text = finalText;
        streamedBytes = 0;
        stopReason = message.path("stopReason").asText(null);
        errorMessage = message.path("errorMessage").asText(null);
        aborted = "aborted".equals(stopReason);
    }

    void fail(Throwable failure) {
        if (terminal) return;
        owner.fail(this);
        finishFailed(failure);
    }

    private void finishFailed(Throwable failure) {
        terminal = true;
        terminalFailure = failure;
        cancelTimeout();
        events.fail(failure);
        messages.clear();
        text = "";
        diagnostics.accept("[activity-failed] id=" + requestId + " reason=" + failure.getMessage());
        if (!accepted) PiThreads.callbacks().execute(() -> acceptance.completeExceptionally(failure));
        PiThreads.callbacks().execute(() -> completion.completeExceptionally(failure));
    }

    private void onAcceptance(PiResponse response, Throwable failure) {
        synchronized (owner.lifecycle) {
            if (terminal) return;
            if (failure != null) {
                failure = PiSupport.unwrap(failure);
                if (failure instanceof RequestRejectedException) {
                    owner.release(this);
                    finishFailed(failure);
                } else fail(failure);
                return;
            }
            accepted = true;
            disposition = dispositionOf(response);
            PiThreads.callbacks().execute(() -> acceptance.complete(response));
            if ("handled".equals(disposition) && queuedControl) {
                fail(new PiException("Input was handled while control input was queued; recreate the client"));
                return;
            }
            completeIfReady();
        }
    }

    private void completeIfReady() {
        if (terminal || !accepted || pendingControls != 0 || !(settled || "handled".equals(disposition))) return;
        boolean handled = "handled".equals(disposition);
        PiResult result = handled ? new PiResult("", List.of(), false, disposition, null, null)
                : new PiResult(text, messages, aborted, disposition, stopReason, errorMessage);
        terminal = true;
        cancelTimeout();
        owner.release(this, handled && runStarted && !settled);
        events.complete();
        messages.clear();
        diagnostics.accept("[activity-complete] id=" + requestId + " disposition=" + disposition
                + " aborted=" + aborted + " retainedBytes=" + messageBytes);
        PiThreads.callbacks().execute(() -> completion.complete(result));
    }

    private void onTimeout() {
        synchronized (owner.lifecycle) {
            if (terminal) return;
            if (owner.isActive(this)) stopRemoteWork();
            fail(new ActivityTimeoutException(requestId));
        }
    }
    private void stopRemoteWork() {
        connection.request("clear_queue").handle((response, failure) -> null)
                .thenCompose(ignored -> connection.request("abort"));
    }

    private boolean withinBudget(long bytes) {
        if (bytes <= config.activityResultLimit()) return true;
        stopRemoteWork();
        fail(new PiException("Activity result exceeded " + config.activityResultLimit() + " bytes; recreate the client"));
        return false;
    }

    private void cancelTimeout() { if (timeoutTask != null) timeoutTask.cancel(false); }

    private static String dispositionOf(PiResponse response) {
        return response.data() == null ? "started" : response.data().path("disposition").asText("started");
    }

    private static String assistantText(JsonNode message) {
        JsonNode content = message.get("content");
        if (content == null) return "";
        if (content.isTextual()) return content.textValue();
        if (!content.isArray()) return "";
        StringBuilder result = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) result.append(block.path("text").textValue());
        }
        return result.toString();
    }
}
