package io.github.idoly.pi.core;

import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiImage;
import io.github.idoly.pi.protocol.PiResponse;

import java.util.List;
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

    /** Sends text-only steering input while this activity is active. */
    default CompletionStage<PiResponse> steer(String message) {
        return steer(message, List.of());
    }

    /** Sends steering input with optional images while this activity is active. */
    CompletionStage<PiResponse> steer(String message, List<PiImage> images);

    /** Queues text-only follow-up input while this activity is active. */
    default CompletionStage<PiResponse> followUp(String message) {
        return followUp(message, List.of());
    }

    /** Queues follow-up input with optional images while this activity is active. */
    CompletionStage<PiResponse> followUp(String message, List<PiImage> images);

    /** Requests cancellation of this activity. */
    CompletionStage<Void> abort();
}
