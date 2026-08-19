package io.github.idoly.pi.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Final activity output.
 *
 * @param text final assistant text from {@code message_end}
 * @param messages complete messages observed during the activity
 * @param aborted whether the assistant reported an aborted stop reason
 */
public record PiResult(String text, List<JsonNode> messages, boolean aborted) {
    public PiResult {
        messages = List.copyOf(messages);
    }
}
