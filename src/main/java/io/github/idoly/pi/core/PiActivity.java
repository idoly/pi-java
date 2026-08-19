package io.github.idoly.pi.core;

import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiResponse;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Represents one prompt from RPC acceptance through {@code agent_settled}.
 */
public interface PiActivity {
    /** Returns the request ID of the root prompt command. */
    String requestId();

    /** Returns events associated with this activity. */
    Flow.Publisher<PiEvent> events();

    /** Completes when pi accepts or rejects the prompt RPC command. */
    CompletionStage<PiResponse> accepted();

    /** Completes after {@code agent_settled} with the final assistant result. */
    CompletionStage<PiResult> completion();

    /** Sends steering input while this activity is active. */
    CompletionStage<PiResponse> steer(String message);

    /** Queues follow-up input while this activity is active. */
    CompletionStage<PiResponse> followUp(String message);

    /** Requests cancellation of this activity. */
    CompletionStage<Void> abort();
}
