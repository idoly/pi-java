package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.protocol.PiImage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record PiCommand(String id, String type, Map<String, ?> arguments) {

    PiCommand {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Command id must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Command type must not be blank");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    static PiCommand prompt(String id, String message) {
        return prompt(id, message, List.of());
    }

    static PiCommand prompt(String id, String message, List<PiImage> images) {
        Objects.requireNonNull(message, "message");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("message", message);
        if (images != null && !images.isEmpty()) {
            arguments.put("images", images.stream()
                    .map(image -> Map.of(
                            "type", "image",
                            "data", image.data(),
                            "mimeType", image.mimeType()))
                    .toList());
        }
        return new PiCommand(id, "prompt", arguments);
    }

    static PiCommand steer(String id, String message) {
        return withMessage(id, "steer", message);
    }

    static PiCommand followUp(String id, String message) {
        return withMessage(id, "follow_up", message);
    }

    static PiCommand simple(String id, String type) {
        return new PiCommand(id, type, Map.of());
    }

    private static PiCommand withMessage(String id, String type, String message) {
        Objects.requireNonNull(message, "message");
        return new PiCommand(id, type, Map.of("message", message));
    }
}
