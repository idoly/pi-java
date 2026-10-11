package io.github.idoly.pi.core;

/** Base failure reported by the Pi Java RPC SDK. */
public class PiException extends RuntimeException {
    public PiException(String message) { super(message); }
    public PiException(String message, Throwable cause) { super(message, cause); }
}
