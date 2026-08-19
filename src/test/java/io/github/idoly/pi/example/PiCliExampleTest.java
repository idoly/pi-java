package io.github.idoly.pi.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.idoly.pi.core.PiActivity;
import io.github.idoly.pi.core.PiClient;
import io.github.idoly.pi.core.PiClientState;
import io.github.idoly.pi.core.PiPrompt;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiCliExampleTest {
    @Test
    void printsHelp() {
        Result result = run("--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Usage:"));
        assertEquals("", result.error());
    }

    @Test
    void printsVersion() {
        Result result = run("--version");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("development"));
    }

    @Test
    void rejectsMissingPrompt() {
        Result result = run();

        assertEquals(2, result.exitCode());
        assertTrue(result.error().contains("Prompt is required"));
    }

    @Test
    void rejectsInvalidTimeout() {
        Result result = run("--timeout", "0", "prompt");

        assertEquals(2, result.exitCode());
        assertTrue(result.error().contains("positive number of seconds"));
    }

    @Test
    void rejectsStdinMarkerInInteractiveMode() {
        Result result = run("--interactive", "-");

        assertEquals(2, result.exitCode());
        assertTrue(result.error().contains("cannot be used with --interactive"));
    }

    @Test
    void rejectsGetStateWithoutObjectData() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> PiCliExample.printSession(client(response("get_state", null)), System.out));

        assertTrue(failure.getMessage().contains("missing data"));
    }

    @Test
    void rejectsGetStateWithoutSessionFile() {
        JsonNode data = JsonNodeFactory.instance.objectNode().put("sessionId", "session-1");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> PiCliExample.printSession(client(response("get_state", data)), System.out));

        assertTrue(failure.getMessage().contains("missing sessionFile"));
    }

    @Test
    void rejectsSwitchSessionWithoutCancelledFlag() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> PiCliExample.switchSession(
                        client(response("switch_session", JsonNodeFactory.instance.objectNode())),
                        Path.of("session.jsonl")));

        assertTrue(failure.getMessage().contains("missing cancelled"));
    }

    private static PiClient client(PiResponse response) {
        return new StubClient(response);
    }

    private static PiResponse response(String command, JsonNode data) {
        JsonNode raw = JsonNodeFactory.instance.objectNode();
        return new PiResponse("request-1", command, true, data, null, raw);
    }

    private static Result run(String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exitCode = PiCliExample.run(
                args,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        return new Result(
                exitCode,
                output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String output, String error) {
    }

    private static final class StubClient implements PiClient {
        private final PiResponse response;

        private StubClient(PiResponse response) {
            this.response = response;
        }

        @Override
        public PiActivity prompt(String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PiActivity prompt(PiPrompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<PiResponse> request(
                String command,
                Map<String, ?> arguments
        ) {
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public Flow.Publisher<PiEvent> events() {
            return subscriber -> { };
        }

        @Override
        public PiClientState state() {
            return PiClientState.IDLE;
        }

        @Override
        public void close() {
        }
    }
}
