package io.github.idoly.pi.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.idoly.pi.ProcessExitException;
import io.github.idoly.pi.RequestRejectedException;
import io.github.idoly.pi.RequestTimeoutException;
import io.github.idoly.pi.core.PiConnection.Request;
import io.github.idoly.pi.protocol.PiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiConnectionTest {
    private PiProcessFixture fixture;
    private PiConnection connection;

    @BeforeEach
    void setUp() {
        fixture = new PiProcessFixture();
        connection = new PiConnection(fixture.process(), 2000, "test");
        connection.start().toCompletableFuture().join();
        fixture.awaitReady();
    }

    @AfterEach
    void tearDown() {
        try { connection.close(); }
        finally { fixture.close(); }
    }

    @Test
    void requestRejectsReservedFields() {
        for (String field : List.of("type", "id")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Request("original", "get_state", Map.of(field, "override")));
        }
    }

    @Test
    void correlatesResponseByIdAndCommand() throws Exception {
        var response = connection.request("get_state").toCompletableFuture();
        Request sent = fixture.awaitCommand(1);
        fixture.emitInbound(success(sent));
        PiResponse result = response.get(1, TimeUnit.SECONDS);
        assertEquals(sent.id(), result.requestId());
        assertEquals("get_state", result.command());
    }

    @Test
    void correlatesOutOfOrderResponsesAcrossRealPipes() throws Exception {
        var first = connection.request("get_state").toCompletableFuture();
        var second = connection.request("get_messages").toCompletableFuture();
        Request a = fixture.awaitCommand(1);
        Request b = fixture.awaitCommand(1);
        fixture.emitInbound(success(b));
        assertEquals(b.id(), second.get(1, TimeUnit.SECONDS).requestId());
        assertFalse(first.isDone());
        fixture.emitInbound(success(a));
        assertEquals(a.id(), first.get(1, TimeUnit.SECONDS).requestId());
    }

    @Test
    void rejectionPreservesTheResponseInTypedException() throws Exception {
        var response = connection.request("get_state").toCompletableFuture();
        Request sent = fixture.awaitCommand(1);
        fixture.emitInbound(new PiResponse(sent.id(), sent.type(), false, null, "busy",
                JsonNodeFactory.instance.objectNode()));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> response.get(1, TimeUnit.SECONDS));
        var rejected = assertInstanceOf(RequestRejectedException.class, failure.getCause());
        assertEquals(sent.id(), rejected.response().requestId());
        assertEquals("busy", rejected.response().error());
    }

    @Test
    void timeoutRemovesThePendingRegistration() throws Exception {
        Request request = Request.simple(connection.nextRequestId(), "get_state");
        var response = connection.request(request, 100).toCompletableFuture();
        fixture.awaitCommand(1);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> response.get(1, TimeUnit.SECONDS));
        assertInstanceOf(RequestTimeoutException.class, failure.getCause());

        // Reusing this internal ID detects a stale registration without inspecting private state.
        var next = connection.request(request).toCompletableFuture();
        Request sent = fixture.awaitCommand(1);
        fixture.emitInbound(success(sent));
        assertTrue(next.get(1, TimeUnit.SECONDS).success());
    }

    @Test
    void failsPendingWhenProcessExits() throws Exception {
        var response = connection.request("get_state").toCompletableFuture();
        fixture.awaitCommand(1);
        fixture.exitProcess(7);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
        var exited = assertInstanceOf(ProcessExitException.class, failure.getCause());
        assertEquals(7, exited.exitCode());
    }

    @Test
    void duplicateIdDoesNotReplaceTheOriginalWaiter() throws Exception {
        Request request = Request.simple("same-id", "get_state");
        var original = connection.request(request).toCompletableFuture();
        fixture.awaitCommand(1);
        var duplicate = connection.request(request).toCompletableFuture();
        ExecutionException failure = assertThrows(ExecutionException.class, () -> duplicate.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        fixture.emitInbound(success(request));
        assertEquals(request.id(), original.get(1, TimeUnit.SECONDS).requestId());
    }

    @Test
    void lateResponseDoesNotCompleteAnotherRequest() throws Exception {
        var expired = connection.request("get_state", Map.of(), 100).toCompletableFuture();
        Request old = fixture.awaitCommand(1);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> expired.get(1, TimeUnit.SECONDS));
        assertInstanceOf(RequestTimeoutException.class, failure.getCause());
        var next = connection.request("get_state").toCompletableFuture();
        Request current = fixture.awaitCommand(1);
        fixture.emitInbound(success(old));
        fixture.emitInbound(success(current));
        assertEquals(current.id(), next.get(1, TimeUnit.SECONDS).requestId());
    }

    @Test
    void rejectsResponseWithMismatchedCommand() throws Exception {
        var response = connection.request("get_state").toCompletableFuture();
        Request sent = fixture.awaitCommand(1);
        fixture.emitInbound(new PiResponse(sent.id(), "get_messages", true, null, null,
                JsonNodeFactory.instance.objectNode()));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> response.get(1, TimeUnit.SECONDS));
        assertInstanceOf(PiException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("command mismatch"));
    }

    @Test
    void closedConnectionCannotRestart() throws Exception {
        connection.close();
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> connection.start().toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertThrows(ExecutionException.class, () -> connection.request("get_state").toCompletableFuture().get(1, TimeUnit.SECONDS));
    }

    @Test
    void repeatedCloseWaitsForTheSameProcessCleanup() throws Exception {
        connection.close(); fixture.close();
        fixture = new PiProcessFixture(true);
        connection = new PiConnection(fixture.process(), 2000, "closing");
        connection.start().toCompletableFuture().join();
        fixture.awaitReady();
        PiConnection closing = connection;
        var first = CompletableFuture.runAsync(closing::close);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        boolean closed = false;
        while (!closed && System.nanoTime() < deadline) {
            closed = closing.request("get_state").toCompletableFuture().isCompletedExceptionally();
            if (!closed) Thread.sleep(1);
        }
        assertTrue(closed);
        var second = CompletableFuture.runAsync(closing::close);
        assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);
    }

    private static PiResponse success(Request command) {
        return new PiResponse(command.id(), command.type(), true, JsonNodeFactory.instance.objectNode(), null,
                JsonNodeFactory.instance.objectNode());
    }
}
