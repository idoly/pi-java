package io.github.idoly.pi.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.idoly.pi.ActivityTimeoutException;
import io.github.idoly.pi.ProcessExitException;
import io.github.idoly.pi.RequestRejectedException;
import io.github.idoly.pi.protocol.PiEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Pi, native tools and a local deterministic provider; no paid model requests. */
@EnabledIfSystemProperty(named = "pi.test.real", matches = "true")
class PiClientIntegrationTest {
    @TempDir Path workspace;

    private PiClient open() throws Exception { return open(true, null); }

    private PiClient open(boolean retainMessages, Consumer<String> diagnostics) throws Exception {
        return open(retainMessages, diagnostics, 1024);
    }

    private PiConfig.Builder settings() throws Exception {
        Path extension = Path.of(getClass().getResource("/pi/rpc-test-extension.ts").toURI());
        return PiConfig.builder(workspace.toString()).extensions(extension.toString()).offline(true)
                .provider("pi-java-test").model("deterministic").requestTimeout(10);
    }

    private PiClient open(boolean retainMessages, Consumer<String> diagnostics, int eventCapacity) throws Exception {
        PiConfig.Builder builder = settings().noSession(true)
                .retainMessages(retainMessages).eventBufferCapacity(eventCapacity).rpcLineLimit(32 * 1024 * 1024)
                .codemode(true).toolSearch(true);
        if (diagnostics != null) builder.diagnostics(diagnostics);
        PiClient client = PiClient.open(builder.build());
        try {
            client.request("set_auto_retry", Map.of("enabled", false)).toCompletableFuture().get(10, TimeUnit.SECONDS);
            return client;
        } catch (Exception failure) {
            client.close();
            throw failure;
        }
    }

    @Test void startupOptionsUsePiSessionAndPromptSemantics() throws Exception {
        String directory = workspace.resolve("sessions").toString();
        PiConfig config = settings().sessionId("sdk-startup").sessionDir(directory).name("SDK launch")
                .thinking("off").tools("read", "grep").excludeTools("grep")
                .systemPrompt("Deterministic test system.")
                .appendSystemPrompt("First appendix.").appendSystemPrompt("Second appendix.")
                .args(List.of("--append-system-prompt", "-p")).build();
        String sessionFile;
        try (PiClient client = PiClient.open(config)) {
            var state = client.request("get_state").toCompletableFuture().get(5, TimeUnit.SECONDS).data();
            assertEquals("sdk-startup", state.path("sessionId").asText());
            assertEquals("SDK launch", state.path("sessionName").asText());
            assertEquals("off", state.path("thinkingLevel").asText());
            sessionFile = state.path("sessionFile").asText();
            String output = client.prompt("PI_TEST_STARTUP").completion().toCompletableFuture().get(5, TimeUnit.SECONDS).text();
            var observed = new ObjectMapper().readTree(output);
            String system = observed.path("systemPrompt").asText();
            assertTrue(system.contains("Deterministic test system."), system);
            assertTrue(system.contains("First appendix."), system);
            assertTrue(system.contains("Second appendix."), system);
            assertTrue(system.indexOf("First appendix.") < system.indexOf("Second appendix."), system);
            assertTrue(system.contains("-p"), system);
            assertEquals(1, observed.path("tools").size());
            assertEquals("read", observed.path("tools").path(0).asText());
        }
        assertTrue(Files.isRegularFile(Path.of(sessionFile)));
        try (PiClient client = PiClient.open(settings().session(sessionFile).sessionDir(directory).build())) {
            var state = client.request("get_state").toCompletableFuture().get(5, TimeUnit.SECONDS).data();
            assertEquals("sdk-startup", state.path("sessionId").asText());
            assertTrue(state.path("messageCount").asInt() > 0);
        }
    }

    @Test void handledCommandCompletesAndClientCanPromptAgain() throws Exception {
        try (PiClient client = open()) {
            assertEquals("handled", client.prompt("/test_handled").completion().toCompletableFuture().get(10, TimeUnit.SECONDS).disposition());
            assertEquals(PiClient.State.IDLE, client.state());
            assertEquals("done: hello", client.prompt("hello").completion().toCompletableFuture().get(10, TimeUnit.SECONDS).text());
        }
    }

    @Test void realAgentUsesPiNativeReadTool() throws Exception {
        Files.writeString(workspace.resolve("first.txt"), "native-read-result");
        try (PiClient client = open()) {
            var result = client.prompt("PI_TEST_READ").completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(result.successful());
            assertTrue(result.text().contains("native-read-result"), result.text());
            assertTrue(result.messages().stream().anyMatch(message -> message.path("toolName").asText().equals("read")));
        }
    }

    @Test void extensionDialogUsesDedicatedResponseChannel() throws Exception {
        try (PiClient client = open()) {
            CompletableFuture<Void> replied = new CompletableFuture<>();
            client.events().subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                public void onNext(PiEvent event) {
                    if (event.type().equals("extension_ui_request") && event.raw().path("method").asText().equals("confirm")) {
                        client.respondToUi(event.raw().path("id").asText(), Map.of("confirmed", true))
                                .whenComplete((ignored, failure) -> { if (failure == null) replied.complete(null); else replied.completeExceptionally(failure); });
                    }
                }
                public void onError(Throwable failure) { replied.completeExceptionally(failure); }
                public void onComplete() {}
            });
            assertEquals("handled", client.prompt("/test_ui").completion().toCompletableFuture().get(10, TimeUnit.SECONDS).disposition());
            replied.get(1, TimeUnit.SECONDS);
        }
    }

    @Test void abortCancelsRunAndClearsQueuedFollowUp() throws Exception {
        try (PiClient client = open()) {
            PiActivity activity = client.prompt("PI_TEST_WAIT");
            waitForEvent(activity.events(), "message_start").get(5, TimeUnit.SECONDS);
            activity.followUp("queued work must not run").toCompletableFuture().get(5, TimeUnit.SECONDS);
            activity.abort().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var result = activity.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(result.aborted());
            assertFalse(result.successful());
            assertEquals(0, client.request("get_state").toCompletableFuture().get(5, TimeUnit.SECONDS).data().path("pendingMessageCount").asInt());
        }
    }

    @Test void cancelsIndependentExtensionRunWithoutRecreatingClient() throws Exception {
        try (PiClient client = open()) {
            var started = waitForEvent(client.events(), "agent_start");
            assertEquals("handled", client.prompt("/test_independent").completion().toCompletableFuture().get(10, TimeUnit.SECONDS).disposition());
            started.get(5, TimeUnit.SECONDS);
            client.abort().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(PiClient.State.IDLE, client.state());
            assertEquals("done: hello", client.prompt("hello").completion().toCompletableFuture().get(5, TimeUnit.SECONDS).text());
        }
    }

    @Test void followUpRunsWithinOneActivity() throws Exception {
        try (PiClient client = open()) {
            PiActivity activity = client.prompt("PI_TEST_WAIT");
            waitForEvent(activity.events(), "message_start").get(5, TimeUnit.SECONDS);
            activity.followUp("follow-up finished").toCompletableFuture().get(5, TimeUnit.SECONDS);
            activity.prompt("/test_release").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("done: follow-up finished", activity.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).text());
        }
    }

    @Test void exposesProviderFailure() throws Exception {
        try (PiClient client = open()) {
            var result = client.prompt("PI_TEST_ERROR").completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("error", result.stopReason());
            assertFalse(result.successful());
            assertEquals("Deterministic fixture failure", result.errorMessage());
        }
    }

    @Test void deadlineRequiresNewClient() throws Exception {
        try (PiClient client = open()) {
            PiActivity activity = client.prompt("PI_TEST_WAIT", 1);
            assertTrue(activity.accepted().toCompletableFuture().get(5, TimeUnit.SECONDS).success());
            var failure = assertThrows(ExecutionException.class, () -> activity.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActivityTimeoutException.class, failure.getCause());
            assertEquals(PiClient.State.FAILED, client.state());
            assertThrows(IllegalStateException.class, () -> client.prompt("next"));
        }
    }

    @Test void codemodeCombinesPiNativeTools() throws Exception {
        Files.writeString(workspace.resolve("first.txt"), "first-value");
        Files.writeString(workspace.resolve("second.txt"), "second-value");
        try (PiClient client = open()) {
            var result = client.prompt("PI_TEST_CODEMODE").completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertTrue(result.successful(), result.toString());
            assertTrue(result.text().contains("first-value") && result.text().contains("second-value"), result.text());
            assertTrue(result.messages().stream().anyMatch(message -> message.path("toolName").asText().equals("codemode")));
        }
    }

    @Test void unexpectedRealProcessExitFailsActivityAndClient() throws Exception {
        try (PiClient client = open()) {
            PiActivity activity = client.prompt("PI_TEST_EXIT");
            var failure = assertThrows(ExecutionException.class, () -> activity.completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertInstanceOf(ProcessExitException.class, failure.getCause());
            assertEquals(PiClient.State.FAILED, client.state());
        }
    }

    @Test void extensionCommandCanBeHandledDuringAnActiveRun() throws Exception {
        try (PiClient client = open()) {
            PiActivity activity = client.prompt("PI_TEST_WAIT");
            waitForEvent(activity.events(), "message_start").get(5, TimeUnit.SECONDS);
            assertEquals("handled", activity.prompt("/test_handled").toCompletableFuture().get(5, TimeUnit.SECONDS).data().path("disposition").asText());
            activity.prompt("/test_release").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("done: PI_TEST_WAIT", activity.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).text());
        }
    }

    @Test void allUiDialogsAndNotificationsUseTheSameEventStream() throws Exception {
        try (PiClient client = open()) {
            Set<String> seen = ConcurrentHashMap.newKeySet();
            CompletableFuture<Void> notifications = new CompletableFuture<>();
            client.events().subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                public void onNext(PiEvent event) {
                    if (!event.type().equals("extension_ui_request")) return;
                    String method = event.raw().path("method").asText();
                    seen.add(method);
                    Map<String, ?> reply = switch(method) {
                        case "select" -> Map.of("value", "B");
                        case "confirm" -> Map.of("confirmed", false);
                        case "input" -> event.raw().path("title").asText().equals("Cancel")
                                ? Map.of("cancelled", true) : Map.of("value", "hello");
                        case "editor" -> Map.of("value", "line1\nline2");
                        default -> null;
                    };
                    if (reply != null) client.respondToUi(event.raw().path("id").asText(), reply)
                            .whenComplete((ignored, failure) -> { if(failure != null) notifications.completeExceptionally(failure); });
                    if (seen.containsAll(List.of("notify", "setStatus", "setWidget", "setTitle", "set_editor_text"))) notifications.complete(null);
                }
                public void onError(Throwable failure) { notifications.completeExceptionally(failure); }
                public void onComplete() {}
            });
            assertEquals("handled", client.prompt("/test_ui_all").completion().toCompletableFuture().get(10, TimeUnit.SECONDS).disposition());
            notifications.get(5, TimeUnit.SECONDS);
            assertTrue(seen.containsAll(List.of("select", "confirm", "input", "editor", "notify", "setStatus", "setWidget", "setTitle", "set_editor_text")));
        }
    }

    @Test void piResolvesDialogTimeoutWithoutClientResponse() throws Exception {
        try (PiClient client = open()) {
            var requested = waitForEvent(client.events(), "extension_ui_request");
            assertEquals("handled", client.prompt("/test_ui_timeout").completion().toCompletableFuture().get(5, TimeUnit.SECONDS).disposition());
            requested.get(1, TimeUnit.SECONDS);
            assertEquals(PiClient.State.IDLE, client.state());
        }
    }

    @Test void queriesPreserveSessionCursorsAndBashEventIds() throws Exception {
        try (PiClient client = open()) {
            for (String command : List.of("get_state", "get_messages", "get_available_models", "get_available_thinking_levels",
                    "get_session_stats", "get_commands", "get_entries", "get_tree", "get_fork_messages", "get_last_assistant_text")) {
                assertTrue(client.request(command).toCompletableFuture().get(5, TimeUnit.SECONDS).success(), command);
            }
            client.request("set_session_name", Map.of("name", "rpc-test")).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("rpc-test", client.request("get_state").toCompletableFuture().get(5, TimeUnit.SECONDS).data().path("sessionName").asText());
            client.prompt("cursor test").completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var entries = client.request("get_entries").toCompletableFuture().get(5, TimeUnit.SECONDS).data().path("entries");
            assertTrue(entries.isArray() && entries.size() > 0);
            String cursor = entries.get(entries.size() - 1).path("id").asText();
            assertEquals(0, client.request("get_entries", Map.of("since", cursor)).toCompletableFuture().get(5, TimeUnit.SECONDS).data().path("entries").size());
            var badCursor = assertThrows(ExecutionException.class, () -> client.request("get_entries", Map.of("since", "missing-cursor")).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertInstanceOf(RequestRejectedException.class, badCursor.getCause());
            CompletableFuture<String> bashId = new CompletableFuture<>();
            client.events().subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                public void onNext(PiEvent event) { if(event.type().equals("bash_execution_update")) bashId.complete(event.raw().path("id").asText()); }
                public void onError(Throwable failure) { bashId.completeExceptionally(failure); }
                public void onComplete() {}
            });
            var bash = client.request("bash", Map.of("command", "echo rpc-bash", "excludeFromContext", true), 10)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(bash.data().path("output").asText().contains("rpc-bash"));
            assertEquals(bash.requestId(), bashId.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void readsImagesWithoutFlatteningRetainedMessageBlocks() throws Exception {
        Files.write(workspace.resolve("image.png"), Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAE0lEQVR4nGP4z8DwnwGM/zMwAAAf7gP9NRsAMwAAAABJRU5ErkJggg=="));
        try (PiClient client = open()) {
            PiResult result = client.prompt("PI_TEST_IMAGE").completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(result.successful());
            assertTrue(result.messages().stream().anyMatch(message -> {
                if (!message.path("role").asText().equals("toolResult")) return false;
                for (var block : message.path("content")) if (block.path("type").asText().equals("image")) return true;
                return false;
            }));
        }
    }

    @Test void acceptsLargeHistoryResponseWithConfiguredLineLimit() throws Exception {
        try (PiClient client = open(false, null)) {
            assertEquals("handled", client.prompt("/test_big_history").completion().toCompletableFuture().get(10, TimeUnit.SECONDS).disposition());
            var entries = client.request("get_entries", Map.of(), 15)
                    .toCompletableFuture().get(15, TimeUnit.SECONDS).data().path("entries");
            boolean found = false;
            for (var entry : entries) {
                if (entry.path("customType").asText().equals("large-fixture")) {
                    assertEquals(17 * 1024 * 1024, entry.path("data").path("value").asText().length());
                    found = true;
                }
            }
            assertTrue(found);
            assertEquals(PiClient.State.IDLE, client.state());
        }
    }

    @Test void blockedDiagnosticsDoNotBlockProtocolOrClose() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        PiClient client = null;
        try {
            client = open(false, line -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 40; i++) client.request("get_state").toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals("done: alive", client.prompt("alive").completion().toCompletableFuture().get(3, TimeUnit.SECONDS).text());
            PiClient closing = client;
            CompletableFuture.runAsync(closing::close).get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); if (client != null) client.close(); }
    }

    @Test void configuredEventCapacityReplaysLargeDeltaStream() throws Exception {
        for (int capacity : List.of(1024, 4096)) {
            try (PiClient client = open(false, null, capacity)) {
                PiActivity activity = client.prompt("PI_TEST_DELTAS");
                PiResult result = activity.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertTrue(result.successful());
                assertEquals(3000, result.text().length());
                CompletableFuture<Integer> deltas = new CompletableFuture<>();
                activity.events().subscribe(new Flow.Subscriber<>() {
                    private int count;
                    public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                    public void onNext(PiEvent event) { if (event.textDelta().isPresent()) count++; }
                    public void onError(Throwable failure) { deltas.completeExceptionally(failure); }
                    public void onComplete() { deltas.complete(count); }
                });
                if (capacity == 1024) {
                    var failure = assertThrows(ExecutionException.class, () -> deltas.get(5, TimeUnit.SECONDS));
                    assertInstanceOf(PiException.class, failure.getCause());
                } else {
                    assertEquals(3000, deltas.get(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test void finalOnlyResultAndDiagnosticsWorkAcrossManyRounds() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (PiClient client = open(false, lines::add)) {
            for (int round = 0; round < 30; round++) {
                PiResult result = client.prompt("round " + round).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertTrue(result.messages().isEmpty());
                assertEquals("done: round " + round, result.text());
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (lines.stream().noneMatch(line -> line.contains("[process-exit]")) && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(lines.stream().anyMatch(line -> line.contains("[request]") && line.contains("id=")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("[process-exit]")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("round 29")), "Diagnostics must not log prompts/results");
    }

    private static CompletableFuture<Void> waitForEvent(Flow.Publisher<PiEvent> events, String type) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        events.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
            public void onNext(PiEvent event) {
                if (event.type().equals(type) && (!type.equals("message_start") || event.raw().path("message").path("role").asText().equals("assistant"))) {
                    result.complete(null); subscription.cancel();
                }
            }
            public void onError(Throwable failure) { result.completeExceptionally(failure); }
            public void onComplete() { if (!result.isDone()) result.completeExceptionally(new IllegalStateException("Event missing: " + type)); }
        });
        return result;
    }
}
