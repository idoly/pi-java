package io.github.idoly.pi.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * A pi event retaining its complete protocol object for forward compatibility.
 */
public record PiEvent(String type, JsonNode raw) {
    public PiEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Event type must not be blank");
        }
        Objects.requireNonNull(raw, "raw");
    }

    /** Returns a text delta when this is a compatible assistant message update. */
    public Optional<String> textDelta() {
        if (!"message_update".equals(type)) return Optional.empty();
        JsonNode event = raw.get("assistantMessageEvent");
        return event != null
                && "text_delta".equals(event.path("type").asText())
                && event.path("delta").isTextual()
                ? Optional.of(event.path("delta").textValue())
                : Optional.empty();
    }
}
