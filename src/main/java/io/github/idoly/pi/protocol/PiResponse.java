package io.github.idoly.pi.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/** Raw-compatible response to an RPC command. */
public record PiResponse(
        String requestId,
        String command,
        boolean success,
        JsonNode data,
        String error,
        JsonNode raw
) {
}
