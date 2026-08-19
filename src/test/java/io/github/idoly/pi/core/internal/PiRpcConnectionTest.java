package io.github.idoly.pi.core.internal;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.idoly.pi.core.PiProcessExitException;
import io.github.idoly.pi.core.PiRequestRejectedException;
import io.github.idoly.pi.core.PiRequestTimeoutException;
import io.github.idoly.pi.protocol.PiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PiRpcConnectionTest {
    private PiScriptedTransport transport;
    private PiRpcConnection client;

    @BeforeEach
    void setUp() {
        transport = new PiScriptedTransport();
        client = new PiRpcConnection(transport, Duration.ofSeconds(1), "test");
        client.start().toCompletableFuture().join();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void correlatesResponseByIdAndCommand() throws Exception {
        PiCommand command = PiCommand.simple(client.nextRequestId(), "get_state");
        var response = client.request(command).toCompletableFuture();
        PiCommand sent = assertInstanceOf(
                PiCommand.class, transport.awaitCommand(Duration.ofSeconds(1)));

        transport.emitInbound(success(sent));

        assertEquals("get_state", response.get(1, TimeUnit.SECONDS).command());
    }

    @Test
    void mapsRejectedResponseToTypedException() throws Exception {
        PiCommand command = PiCommand.prompt(client.nextRequestId(), "hello");
        var response = client.request(command).toCompletableFuture();
        transport.awaitCommand(Duration.ofSeconds(1));
        transport.emitInbound(new PiResponse(
                command.id(), "prompt", false, null, "busy", JsonNodeFactory.instance.objectNode()));

        ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class, () -> response.get(1, TimeUnit.SECONDS));
        assertInstanceOf(PiRequestRejectedException.class, failure.getCause());
    }

    @Test
    void timesOutAndRemovesPendingRequest() throws Exception {
        PiCommand command = PiCommand.simple(client.nextRequestId(), "get_state");
        var response = client.request(command, Duration.ofMillis(30)).toCompletableFuture();
        transport.awaitCommand(Duration.ofSeconds(1));

        ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class, () -> response.get(1, TimeUnit.SECONDS));
        assertInstanceOf(PiRequestTimeoutException.class, failure.getCause());
    }

    @Test
    void failsPendingRequestWhenProcessExits() throws Exception {
        var response = client.request("get_state").toCompletableFuture();
        transport.awaitCommand(Duration.ofSeconds(1));

        transport.exitProcess(7);

        ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class, () -> response.get(1, TimeUnit.SECONDS));
        assertInstanceOf(PiProcessExitException.class, failure.getCause());
    }

    private static PiResponse success(PiCommand command) {
        return new PiResponse(
                command.id(), command.type(), true,
                JsonNodeFactory.instance.objectNode(), null,
                JsonNodeFactory.instance.objectNode());
    }
}
