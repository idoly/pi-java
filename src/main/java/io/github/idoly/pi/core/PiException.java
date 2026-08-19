package io.github.idoly.pi.core;

/** Base class for failures reported by pi-java. */
public class PiException extends RuntimeException {
    public PiException(String message) {
        super(message);
    }

    public PiException(String message, Throwable cause) {
        super(message, cause);
    }
}
