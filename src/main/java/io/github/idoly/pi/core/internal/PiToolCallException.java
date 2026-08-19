package io.github.idoly.pi.core.internal;

final class PiToolCallException extends RuntimeException {
    PiToolCallException(String message) {
        super(message);
    }

    PiToolCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
