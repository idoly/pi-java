package io.github.idoly.pi.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/** Final output and Pi stop reason. messages is empty unless retainMessages was enabled. */
public record PiResult(String text, List<JsonNode> messages, boolean aborted,
                       String disposition, String stopReason, String errorMessage) {
    public PiResult {
        Objects.requireNonNull(text, "text");
        messages = List.copyOf(messages);
    }
    /** A settled run may still have failed, been truncated, deferred, or aborted. */
    public boolean successful() {
        return !aborted && ("handled".equals(disposition)
                || "stop".equals(stopReason) || "toolUse".equals(stopReason));
    }
}
