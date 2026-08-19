package io.github.idoly.pi.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.idoly.pi.core.PiClient;
import io.github.idoly.pi.core.PiClientState;
import io.github.idoly.pi.core.PiPrompt;
import io.github.idoly.pi.core.PiActivity;
import io.github.idoly.pi.core.PiActivityTimeoutException;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiClientActivityTest {
    private PiScriptedTransport transport;
    private PiClient client;

    @BeforeEach
    void setUp() {
        transport = new PiScriptedTransport();
        client = PiClientFactory.createForTest(transport);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void completesOnlyAfterAcceptedAndAgentSettled() throws Exception {
        PiActivity activity = client.prompt("hello");
        PiCommand command = assertInstanceOf(
                PiCommand.class, transport.awaitCommand(Duration.ofSeconds(1)));

        transport.emitInbound(success(command));
        transport.emitInbound(event("agent_end"));

        assertFalse(activity.completion().toCompletableFuture().isDone());

        transport.emitInbound(messageStart());
        transport.emitInbound(textDelta("streamed"));
        transport.emitInbound(messageEnd("authoritative"));
        transport.emitInbound(event("agent_settled"));

        assertEquals("authoritative",
                activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).text());
        assertEquals(PiClientState.IDLE, client.state());
    }

    @Test
    void ownsActivityCommands() throws Exception {
        PiActivity activity = client.prompt("hello");
        PiCommand prompt = assertInstanceOf(
                PiCommand.class, transport.awaitCommand(Duration.ofSeconds(1)));
        transport.emitInbound(success(prompt));

        var steer = activity.steer("focus");
        PiCommand steerCommand = assertInstanceOf(
                PiCommand.class, transport.awaitCommand(Duration.ofSeconds(1)));
        assertEquals("steer", steerCommand.type());
        transport.emitInbound(success(steerCommand));
        steer.toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertThrows(IllegalArgumentException.class, () -> client.request("abort"));
    }

    @Test
    void timesOutAndAborts() throws Exception {
        PiPrompt prompt = new PiPrompt("hello", List.of(), Duration.ofMillis(20));
        PiActivity activity = client.prompt(prompt);
        PiCommand promptCommand = assertInstanceOf(
                PiCommand.class, transport.awaitCommand(Duration.ofSeconds(1)));
        transport.emitInbound(success(promptCommand));

        Exception failure = assertThrows(Exception.class, () ->
                activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertInstanceOf(PiActivityTimeoutException.class, failure.getCause());
        PiCommand abortCommand = assertInstanceOf(
                PiCommand.class, transport.awaitCommand(Duration.ofSeconds(1)));

        assertEquals("abort", abortCommand.type());
        assertEquals(PiClientState.FAILED, client.state());
    }

    @Test
    void failsOnUnexpectedExit() throws Exception {
        transport.exitProcess(0);

        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (client.state() != PiClientState.FAILED && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertEquals(PiClientState.FAILED, client.state());
    }

    @Test
    void replaysEarlyEvents() throws Exception {
        PiActivity activity = client.prompt("hello");
        PiCommand command = assertInstanceOf(
                PiCommand.class, transport.awaitCommand(Duration.ofSeconds(1)));
        transport.emitInbound(success(command));
        transport.emitInbound(event("agent_start"));
        transport.emitInbound(event("agent_settled"));
        activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);

        RecordingSubscriber subscriber = new RecordingSubscriber();
        activity.events().subscribe(subscriber);
        subscriber.completed.get(1, TimeUnit.SECONDS);

        assertEquals(List.of("agent_start", "agent_settled"),
                subscriber.events.stream().map(PiEvent::type).toList());
    }

    private static PiResponse success(PiCommand command) {
        ObjectNode raw = JsonNodeFactory.instance.objectNode();
        return new PiResponse(command.id(), command.type(), true, raw, null, raw);
    }

    private static PiEvent event(String type) {
        ObjectNode raw = JsonNodeFactory.instance.objectNode().put("type", type);
        return new PiEvent(type, raw);
    }

    private static PiEvent messageStart() {
        ObjectNode raw = JsonNodeFactory.instance.objectNode().put("type", "message_start");
        raw.set("message", JsonNodeFactory.instance.objectNode().put("role", "assistant"));
        return new PiEvent("message_start", raw);
    }

    private static PiEvent textDelta(String value) {
        ObjectNode delta = JsonNodeFactory.instance.objectNode()
                .put("type", "text_delta")
                .put("delta", value);
        ObjectNode raw = JsonNodeFactory.instance.objectNode().put("type", "message_update");
        raw.set("assistantMessageEvent", delta);
        return new PiEvent("message_update", raw);
    }

    private static PiEvent messageEnd(String text) {
        ObjectNode block = JsonNodeFactory.instance.objectNode()
                .put("type", "text")
                .put("text", text);
        ObjectNode message = JsonNodeFactory.instance.objectNode()
                .put("role", "assistant")
                .put("stopReason", "stop");
        message.set("content", JsonNodeFactory.instance.arrayNode().add(block));
        ObjectNode raw = JsonNodeFactory.instance.objectNode().put("type", "message_end");
        raw.set("message", message);
        return new PiEvent("message_end", raw);
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<PiEvent> {
        private final List<PiEvent> events = new ArrayList<>();
        private final CompletableFuture<Void> completed = new CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(PiEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            completed.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completed.complete(null);
        }
    }
}
