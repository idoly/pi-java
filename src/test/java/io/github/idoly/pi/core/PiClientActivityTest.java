package io.github.idoly.pi.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.idoly.pi.core.PiConnection.Request;
import io.github.idoly.pi.ActivityTimeoutException;
import io.github.idoly.pi.RequestRejectedException;
import io.github.idoly.pi.RequestTimeoutException;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiImage;
import io.github.idoly.pi.protocol.PiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiClientActivityTest {
    private PiProcessFixture transport;
    private PiClient client;

    @BeforeEach
    void setUp() {
        transport = new PiProcessFixture();
        client = transport.openClient();
    }

    @AfterEach
    void tearDown() {
        try { if (client != null) client.close(); }
        finally { if (transport != null) transport.close(); }
    }

    @Test
    void completesOnlyAfterAcceptedAndAgentSettled() throws Exception {
        for (boolean settledFirst : List.of(false, true)) {
            PiActivity activity = client.prompt("hello");
            Request command = transport.awaitCommand(1);
            transport.emitInbound(messageStart());
            transport.emitInbound(textDelta("streamed"));
            transport.emitInbound(messageEnd("authoritative"));
            transport.emitInbound(event("agent_end"));
            if (settledFirst) transport.emitInbound(event("agent_settled"));
            else transport.emitInbound(success(command));
            var inspected = client.request("get_state");
            transport.emitInbound(success(transport.awaitCommand(1)));
            inspected.toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertFalse(activity.completion().toCompletableFuture().isDone());

            if (settledFirst) transport.emitInbound(success(command));
            else transport.emitInbound(event("agent_settled"));
            assertEquals("authoritative",
                    activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).text());
            assertEquals(PiClient.State.IDLE, client.state());
        }
    }

    @Test
    void settledWithoutAnAssistantResultIsIncompleteAndNotSuccessful() throws Exception {
        PiActivity activity = client.prompt("no assistant response");
        transport.emitInbound(success(transport.awaitCommand(1)));
        transport.emitInbound(event("agent_settled"));
        PiResult result = activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertFalse(result.successful());
        assertNull(result.stopReason());
        assertEquals(PiClient.State.IDLE, client.state());
    }

    @Test
    void finalMessageContentIsAuthoritativeEvenWhenStreamedTextWasNonEmpty() throws Exception {
        for (boolean thinkingOnly : List.of(false, true)) {
            PiActivity activity = client.prompt("final content");
            transport.emitInbound(success(transport.awaitCommand(1)));
            transport.emitInbound(messageStart());
            transport.emitInbound(textDelta("discarded streamed text"));
            PiEvent end = messageEnd("unused");
            var content = JsonNodeFactory.instance.arrayNode();
            if (thinkingOnly) content.add(JsonNodeFactory.instance.objectNode().put("type", "thinking").put("thinking", "internal"));
            ((ObjectNode) end.raw().path("message")).set("content", content);
            transport.emitInbound(end);
            transport.emitInbound(event("agent_settled"));
            assertEquals("", activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).text());
        }
    }

    @Test
    void ownsActivityCommands() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> client.prompt("invalid timeout", 0));
        assertThrows(IllegalArgumentException.class, () -> client.request("get_state", Map.of(), -1));
        assertEquals(PiClient.State.IDLE, client.state());
        PiActivity activity = client.prompt("hello", List.of(new PiImage("YWJj", "image/png")), 5);
        Request prompt = transport.awaitCommand(1);
        assertEquals("image", ((Map<?, ?>) ((List<?>) prompt.arguments().get("images")).get(0)).get("type"));
        transport.emitInbound(success(prompt));

        var steer = activity.steer(
                "focus", List.of(new PiImage("YWJj", "image/png")));
        Request steerCommand = transport.awaitCommand(1);
        assertEquals("steer", steerCommand.type());
        assertEquals("image", ((List<?>) steerCommand.arguments().get("images")).stream()
                .map(value -> (Map<?, ?>) value)
                .findFirst()
                .orElseThrow()
                .get("type"));
        transport.emitInbound(success(steerCommand));
        steer.toCompletableFuture().get(1, TimeUnit.SECONDS);

        var followUp = activity.followUp("then verify", List.of());
        Request followUpCommand = transport.awaitCommand(1);
        assertEquals("follow_up", followUpCommand.type());
        transport.emitInbound(success(followUpCommand));
        followUp.toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertThrows(IllegalArgumentException.class, () -> client.request("abort"));
    }

    @Test
    void timesOutAndAborts() throws Exception {
        PiActivity activity = client.prompt("hello", 1);
        Request promptCommand = transport.awaitCommand(1);
        transport.emitInbound(success(promptCommand));

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                activity.completion().toCompletableFuture().get(3, TimeUnit.SECONDS));
        assertInstanceOf(ActivityTimeoutException.class, failure.getCause());
        Request clear = transport.awaitCommand(1);
        assertEquals("clear_queue", clear.type());
        transport.emitInbound(success(clear));
        Request abortCommand = transport.awaitCommand(1);
        assertEquals("abort", abortCommand.type());
        assertEquals(PiClient.State.FAILED, client.state());
    }

    @Test
    void failsOnUnexpectedExit() throws Exception {
        transport.exitProcess(0);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (client.state() != PiClient.State.FAILED && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertEquals(PiClient.State.FAILED, client.state());
    }

    @Test
    void closeRemainsTerminatedWhenCalledMoreThanOnce() {
        client.close();
        client.close();

        assertEquals(PiClient.State.TERMINATED, client.state());
    }

    @Test
    void replaysEarlyEvents() throws Exception {
        PiActivity activity = client.prompt("hello");
        Request command = transport.awaitCommand(1);
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

    @Test
    void handledInputCompletesWithoutSettledAndLeavesClientUsable() throws Exception {
        PiActivity activity = client.prompt("/local-command");
        Request command = transport.awaitCommand(1);
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("disposition", "handled");
        transport.emitInbound(new PiResponse(command.id(), "prompt", true, data, null, data));
        assertEquals("handled", activity.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS).data().path("disposition").asText());
        var result = activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals("handled", result.disposition());
        assertTrue(result.successful());
        assertEquals(PiClient.State.IDLE, client.state());
        client.prompt("next task");
    }

    @Test
    void handledInputDoesNotClaimIndependentExtensionRun() throws Exception {
        PiActivity activity = client.prompt("/starts-independent-work");
        Request command = transport.awaitCommand(1);
        transport.emitInbound(event("agent_start"));
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("disposition", "handled");
        transport.emitInbound(new PiResponse(command.id(), "prompt", true, data, null, data));
        assertEquals("handled", activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).disposition());
        assertEquals(PiClient.State.ACTIVE, client.state());
        assertThrows(IllegalStateException.class, () -> client.prompt("overlap"));
        transport.emitInbound(event("agent_settled"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (client.state() != PiClient.State.IDLE && System.nanoTime() < deadline) Thread.sleep(2);
        assertEquals(PiClient.State.IDLE, client.state());
        client.prompt("after independent work");
    }

    @Test
    void unknownAcceptanceFailsClientAndRejectsNextPrompt() throws Exception {
        client.close();
        transport.close();
        transport = new PiProcessFixture();
        PiClient implementation = new PiClient(new PiConnection(transport.process(), 60, "timeout"),
                PiConfig.builder().build(), ignored -> {});
        implementation.start().toCompletableFuture().join();
        transport.awaitReady();
        client = implementation;
        PiActivity first = client.prompt("old task");
        Request command = transport.awaitCommand(1);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> first.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertInstanceOf(RequestTimeoutException.class, failure.getCause());
        assertEquals(PiClient.State.FAILED, client.state());
        transport.emitInbound(success(command));
        transport.emitInbound(event("agent_settled"));
        assertThrows(IllegalStateException.class, () -> client.prompt("new task"));
        assertThrows(IllegalStateException.class, () -> client.request("get_state"));
    }

    @Test
    void explicitRejectionReleasesActivity() throws Exception {
        PiActivity activity = client.prompt("rejected");
        Request command = transport.awaitCommand(1);
        transport.emitInbound(new PiResponse(command.id(), "prompt", false, null, "rejected", JsonNodeFactory.instance.objectNode()));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertInstanceOf(RequestRejectedException.class, failure.getCause());
        assertEquals(PiClient.State.IDLE, client.state());
    }

    @Test
    void finalSettledDeterminesAbortEvenWithoutAssistantMessage() throws Exception {
        PiActivity activity = client.prompt("cancel early");
        transport.emitInbound(success(transport.awaitCommand(1)));
        ObjectNode settled = JsonNodeFactory.instance.objectNode().put("type", "agent_settled").put("aborted", true);
        transport.emitInbound(new PiEvent("agent_settled", settled));
        var result = activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertTrue(result.aborted());
        assertFalse(result.successful());
    }

    @Test
    void classifiesFinalAssistantStopReasons() throws Exception {
        for (String reason : List.of("error", "length", "deferred", "stop")) {
            PiActivity activity = client.prompt(reason);
            transport.emitInbound(success(transport.awaitCommand(1)));
            PiEvent end = messageEnd("partial");
            ((ObjectNode) end.raw().path("message")).put("stopReason", reason).put("errorMessage", "provider error");
            transport.emitInbound(end);
            transport.emitInbound(event("agent_settled"));
            var result = activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals("stop".equals(reason), result.successful());
            assertEquals(reason, result.stopReason());
        }
    }

    @Test
    void abortClearsQueuedWorkAndIsIdempotent() throws Exception {
        PiActivity activity = client.prompt("cancel");
        transport.emitInbound(success(transport.awaitCommand(1)));
        var aborted = activity.abort();
        assertEquals(aborted, activity.abort());
        Request clear = transport.awaitCommand(1);
        assertEquals("clear_queue", clear.type());
        transport.emitInbound(success(clear));
        Request abort = transport.awaitCommand(1);
        assertEquals("abort", abort.type());
        transport.emitInbound(event("agent_settled"));
        transport.emitInbound(success(abort));
        aborted.toCompletableFuture().get(1, TimeUnit.SECONDS);
        activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        activity.abort().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, () -> activity.followUp("late"));
    }

    @Test
    void preventsSessionChangesDuringActivityAndPromptsDuringSessionChanges() throws Exception {
        var switching = client.request("switch_session", Map.of("sessionPath", "test.jsonl"));
        Request command = transport.awaitCommand(1);
        assertThrows(IllegalStateException.class, () -> client.prompt("during switch"));
        transport.emitInbound(success(command));
        switching.toCompletableFuture().get(1, TimeUnit.SECONDS);
        client.prompt("after switch");
        assertThrows(IllegalStateException.class, () -> client.request("new_session"));
    }

    @Test
    void blockingAcceptanceCallbackDoesNotBlockSettling() throws Exception {
        PiActivity activity = client.prompt("hello");
        Request command = transport.awaitCommand(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        activity.accepted().thenRun(() -> {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            transport.emitInbound(success(command));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            transport.emitInbound(event("agent_settled"));
            activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(PiClient.State.IDLE, client.state());
        } finally { release.countDown(); }
    }

    @Test
    void blockingRpcCallbackDoesNotBlockActivityEvents() throws Exception {
        PiActivity activity = client.prompt("hello");
        transport.emitInbound(success(transport.awaitCommand(1)));
        activity.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var callback = client.request("get_state").thenRun(() -> {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        try {
            transport.emitInbound(success(transport.awaitCommand(1)));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            transport.emitInbound(event("agent_settled"));
            activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(PiClient.State.IDLE, client.state());
        } finally { release.countDown(); }
        callback.toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test
    void closeRacingWithPromptOrExitPreservesTerminatedState() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                try (PiProcessFixture localTransport = new PiProcessFixture()) {
                    PiClient local = localTransport.openClient();
                    CountDownLatch start = new CountDownLatch(1);
                    var prompt = executor.submit(() -> {
                        start.await();
                        try { return local.prompt("race"); } catch (IllegalStateException e) { return null; }
                    });
                    var close = executor.submit(() -> { start.await(); localTransport.exitProcess(7); local.close(); return null; });
                    start.countDown();
                    PiActivity activity = prompt.get(2, TimeUnit.SECONDS);
                    close.get(2, TimeUnit.SECONDS);
                    assertEquals(PiClient.State.TERMINATED, local.state());
                    if (activity != null) assertThrows(ExecutionException.class, () -> activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
                }
            }
        } finally { executor.shutdownNow(); }
    }

    @Test
    void limitsCumulativeResultAndLiveTextBeforeCompletion() throws Exception {
        for (boolean streaming : List.of(false, true)) {
            client.close(); transport.close();
            transport = new PiProcessFixture();
            PiConfig limitedConfig = PiConfig.builder()
                    .retainMessages(true).activityResultLimit(512).build();
            PiClient limited = new PiClient(new PiConnection(transport.process(), 2000, "budget"), limitedConfig, ignored -> {});
            limited.start().toCompletableFuture().join();
            transport.awaitReady();
            client = limited;
            PiActivity activity = client.prompt("large result");
            transport.emitInbound(success(transport.awaitCommand(1)));
            activity.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS);
            if (streaming) transport.emitInbound(textDelta("x".repeat(513)));
            else for (int i = 0; i < 4; i++) transport.emitInbound(messageEnd("x".repeat(150)));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("512 bytes"));
            assertEquals(PiClient.State.FAILED, client.state());
            assertTrue(activity.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS).success());
            assertEquals("clear_queue", transport.awaitCommand(1).type());
        }
    }

    @Test
    void lateQueuedFollowUpFailsAndDoesNotReleaseClientForAnotherPrompt() throws Exception {
        PiActivity activity = client.prompt("first");
        transport.emitInbound(success(transport.awaitCommand(1)));
        activity.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS);
        var follow = activity.followUp("continuation");
        Request command = transport.awaitCommand(1);
        transport.emitInbound(event("agent_settled"));
        assertThrows(IllegalStateException.class, () -> client.prompt("overlap"));
        transport.emitInbound(success(command));
        assertThrows(ExecutionException.class, () -> follow.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertTrue(activity.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS).success());
        assertEquals(PiClient.State.FAILED, client.state());
        assertThrows(IllegalStateException.class, () -> client.prompt("next"));
    }

    @Test
    void rejectedLateControlDoesNotFailTheFinishedRun() throws Exception {
        PiActivity activity = client.prompt("first");
        transport.emitInbound(success(transport.awaitCommand(1)));
        activity.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS);
        var follow = activity.followUp("rejected continuation");
        Request command = transport.awaitCommand(1);
        transport.emitInbound(messageEnd("done"));
        transport.emitInbound(event("agent_settled"));
        transport.emitInbound(new PiResponse(command.id(), command.type(), false, null, "rejected", JsonNodeFactory.instance.objectNode()));
        assertThrows(ExecutionException.class, () -> follow.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals("done", activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).text());
        assertEquals(PiClient.State.IDLE, client.state());
    }

    @Test
    void abortKeepsClientOwnedUntilItsResponseArrives() throws Exception {
        PiActivity activity = client.prompt("first");
        transport.emitInbound(success(transport.awaitCommand(1)));
        activity.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS);
        var aborting = activity.abort();
        transport.emitInbound(success(transport.awaitCommand(1)));
        Request command = transport.awaitCommand(1);
        assertEquals("abort", command.type());
        transport.emitInbound(event("agent_settled"));
        assertThrows(IllegalStateException.class, () -> client.prompt("overlap"));
        transport.emitInbound(success(command));
        aborting.toCompletableFuture().get(1, TimeUnit.SECONDS);
        activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        client.prompt("next");
    }

    @Test
    void sessionAbortCancelsIndependentRunWithoutClosingClient() throws Exception {
        PiActivity activity = client.prompt("/independent");
        Request prompt = transport.awaitCommand(1);
        transport.emitInbound(event("agent_start"));
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("disposition", "handled");
        transport.emitInbound(new PiResponse(prompt.id(), "prompt", true, data, null, data));
        activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        var aborting = client.abort();
        assertEquals(aborting, client.abort());
        Request clear = transport.awaitCommand(1);
        assertEquals("clear_queue", clear.type());
        transport.emitInbound(success(clear));
        Request abort = transport.awaitCommand(1);
        assertEquals("abort", abort.type());
        transport.emitInbound(event("agent_settled"));
        transport.emitInbound(success(abort));
        aborting.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(PiClient.State.IDLE, client.state());
        client.prompt("next");
    }

    @Test
    void concurrentCloseWaitsForTheSameResourceCleanup() throws Exception {
        try (PiProcessFixture fixture = new PiProcessFixture(true)) {
            PiClient local = fixture.openClient();
            var first = CompletableFuture.runAsync(local::close);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (local.state() != PiClient.State.STOPPING && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(PiClient.State.STOPPING, local.state());
            // Resource cleanup must not monopolize the client object's monitor.
            CompletableFuture.runAsync(() -> { synchronized (local) { } }).get(100, TimeUnit.MILLISECONDS);
            var second = CompletableFuture.runAsync(local::close);
            assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertEquals(PiClient.State.TERMINATED, local.state());
        }
    }

    @Test
    void exclusiveFailureDuringIndependentRunKeepsClientFailed() throws Exception {
        var running = client.request("bash", Map.of("command", "unanswered"), 5);
        Request shell = transport.awaitCommand(1);
        transport.emitInbound(event("agent_start"));
        var inspected = client.request("get_state");
        transport.emitInbound(success(transport.awaitCommand(1)));
        inspected.toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(PiClient.State.ACTIVE, client.state());
        transport.emitInbound(new PiResponse(shell.id(), "get_messages", true, null, null,
                JsonNodeFactory.instance.objectNode()));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> running.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("command mismatch"));
        assertEquals(PiClient.State.FAILED, client.state());
        transport.emitInbound(event("agent_settled"));
        assertThrows(IllegalStateException.class, () -> client.prompt("next"));
    }

    @Test
    void rawBashOwnsSessionUntilResponseButAllowsInspectionAndCancellation() throws Exception {
        var running = client.request("bash", Map.of("command", "echo test", "excludeFromContext", true), 2);
        Request bash = transport.awaitCommand(1);
        assertEquals("echo test", bash.arguments().get("command"));
        assertThrows(IllegalStateException.class, () -> client.prompt("overlap"));
        assertThrows(IllegalStateException.class, () -> client.request("new_session"));
        var state = client.request("get_state");
        transport.emitInbound(success(transport.awaitCommand(1)));
        state.toCompletableFuture().get(1, TimeUnit.SECONDS);
        var cancel = client.request("abort_bash");
        transport.emitInbound(success(transport.awaitCommand(1)));
        cancel.toCompletableFuture().get(1, TimeUnit.SECONDS);
        transport.emitInbound(success(bash));
        running.toCompletableFuture().get(1, TimeUnit.SECONDS);
        client.prompt("next");
    }

    @Test
    void extensionPromptDuringActivityDoesNotCreateAnotherActivity() throws Exception {
        PiActivity activity = client.prompt("root");
        transport.emitInbound(success(transport.awaitCommand(1)));
        activity.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS);
        var commandResult = activity.prompt("/test_handled");
        Request command = transport.awaitCommand(1);
        assertEquals("prompt", command.type());
        assertEquals("steer", command.arguments().get("streamingBehavior"));
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("disposition", "handled");
        transport.emitInbound(new PiResponse(command.id(), "prompt", true, data, null, data));
        commandResult.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(PiClient.State.ACTIVE, client.state());
        assertFalse(activity.completion().toCompletableFuture().isDone());
        transport.emitInbound(messageEnd("root result"));
        transport.emitInbound(event("agent_settled"));
        assertEquals("root result", activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).text());
    }

    @Test
    void uiRepliesPreserveDialogIdAndValidateValues() throws Exception {
        for (Map<String, ?> values : List.of(Map.of("value", "selected"), Map.of("value", "line1\nline2"),
                Map.of("confirmed", false), Map.of("cancelled", true))) {
            client.respondToUi("dialog", values).toCompletableFuture().get(1, TimeUnit.SECONDS);
            Request command = transport.awaitCommand(1);
            assertEquals("dialog", command.id());
            assertEquals("extension_ui_response", command.type());
            assertEquals(values, command.arguments());
        }
        for (Map<String, ?> invalid : List.<Map<String, ?>>of(Map.of(), Map.of("cancelled", false), Map.of("confirmed", "yes"),
                Map.of("value", 3), Map.of("type", "override"), Map.of("value", "x", "cancelled", true))) {
            assertThrows(IllegalArgumentException.class, () -> client.respondToUi("dialog", invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> client.respondToUi("", Map.of("cancelled", true)));
        assertThrows(IllegalArgumentException.class, () -> client.request("extension_ui_response"));
    }

    @Test
    void finalOnlyIgnoresLargeToolHistoryWhileFullRetentionEnforcesBudget() throws Exception {
        for (boolean retain : List.of(false, true)) {
            client.close(); transport.close(); transport = new PiProcessFixture();
            PiConfig config = PiConfig.builder().retainMessages(retain)
                    .activityResultLimit(1024).eventBufferLimit(4096).build();
            client = new PiClient(new PiConnection(transport.process(), 2000, "retain"), config, ignored -> {});
            client.start().toCompletableFuture().join();
            transport.awaitReady();
            PiActivity activity = client.prompt("history");
            transport.emitInbound(success(transport.awaitCommand(1)));
            activity.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS);
            for (int i = 0; i < 50; i++) {
                ObjectNode message = JsonNodeFactory.instance.objectNode().put("role", "toolResult").put("content", "x".repeat(8192));
                ObjectNode raw = JsonNodeFactory.instance.objectNode().put("type", "message_end"); raw.set("message", message);
                transport.emitInbound(new PiEvent("message_end", raw));
            }
            transport.emitInbound(messageEnd("final")); transport.emitInbound(event("agent_settled"));
            if (retain) {
                assertThrows(ExecutionException.class, () -> activity.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
                assertEquals(PiClient.State.FAILED, client.state());
            } else {
                PiResult result = activity.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertEquals("final", result.text()); assertTrue(result.messages().isEmpty());
                assertEquals(PiClient.State.IDLE, client.state());
            }
        }
    }

    @Test
    void slowEventSubscriptionFailsByBytesWithoutFailingActivity() throws Exception {
        client.close(); transport.close(); transport = new PiProcessFixture();
        PiConfig config = PiConfig.builder().eventBufferLimit(256).build();
        client = new PiClient(new PiConnection(transport.process(), 2000, "bytes"), config, ignored -> {});
        client.start().toCompletableFuture().join();
        transport.awaitReady();
        PiActivity activity = client.prompt("events");
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        activity.events().subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) {}
            public void onNext(PiEvent event) {}
            public void onError(Throwable error) { failure.complete(error); }
            public void onComplete() {}
        });
        transport.emitInbound(success(transport.awaitCommand(1)));
        transport.emitInbound(textDelta("x".repeat(300)));
        assertTrue(failure.get(1, TimeUnit.SECONDS).getMessage().contains("256 bytes"));
        transport.emitInbound(messageEnd("done")); transport.emitInbound(event("agent_settled"));
        assertEquals("done", activity.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).text());
    }

    private static PiResponse success(Request command) {
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
