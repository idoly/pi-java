package io.github.idoly.pi.core;

import io.github.idoly.pi.protocol.PiImage;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A prompt message, optional base64-encoded images, and its activity timeout.
 */
public record PiPrompt(
        String message,
        List<PiImage> images,
        Duration timeout
) {
    public PiPrompt {
        Objects.requireNonNull(message, "message");
        images = images == null ? List.of() : List.copyOf(images);
        requirePositive(timeout, "timeout");
    }

    /** Creates a text-only prompt with a ten-minute timeout. */
    public static PiPrompt text(String message) {
        return new PiPrompt(message, List.of(), Duration.ofMinutes(10));
    }

    private static void requirePositive(Duration duration, String fieldName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
