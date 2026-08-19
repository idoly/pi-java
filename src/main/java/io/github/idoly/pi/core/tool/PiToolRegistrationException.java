package io.github.idoly.pi.core.tool;

import io.github.idoly.pi.core.PiException;

/** Indicates that one or more {@code @PiTool} methods cannot be registered safely. */
public final class PiToolRegistrationException extends PiException {
    public PiToolRegistrationException(String message) {
        super(message);
    }

    public PiToolRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
