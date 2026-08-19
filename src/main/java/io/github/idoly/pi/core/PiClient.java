package io.github.idoly.pi.core;

import io.github.idoly.pi.core.internal.PiClientFactory;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiResponse;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Owns one workspace, one pi RPC process, and its current session.
 *
 * <p>A client permits one active {@link PiActivity} at a time. Different clients may run
 * concurrently. Callers must close every client.</p>
 */
public interface PiClient extends AutoCloseable {
    /**
     * Opens a client and performs a {@code get_state} readiness check before returning.
     *
     * @param workspace workspace used as the pi process working directory
     * @param toolInstances objects containing public {@code @PiTool} methods
     * @return a ready, idle client
     */
    static PiClient open(Path workspace, Object... toolInstances) {
        return PiClientFactory.open(workspace, toolInstances);
    }

    /** Starts an activity for a text prompt. */
    PiActivity prompt(String message);

    /** Starts an activity for a prompt with explicit images and timeout. */
    PiActivity prompt(PiPrompt prompt);

    /** Sends a non-activity RPC command without arguments. */
    default CompletionStage<PiResponse> request(String command) {
        return request(command, Map.of());
    }

    /**
     * Sends a non-activity RPC command.
     *
     * @throws IllegalArgumentException if the command belongs to {@link PiActivity}
     */
    CompletionStage<PiResponse> request(String command, Map<String, ?> arguments);

    /** Returns the process-wide event stream. */
    Flow.Publisher<PiEvent> events();

    /** Returns the current lifecycle state. */
    PiClientState state();

    /** Closes the RPC connection, pi process, and optional Tool bridge. */
    @Override
    void close();
}
