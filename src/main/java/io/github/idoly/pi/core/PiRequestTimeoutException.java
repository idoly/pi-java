package io.github.idoly.pi.core;

/** Indicates that an RPC command did not receive a response before its timeout. */
public final class PiRequestTimeoutException extends PiException {
    public PiRequestTimeoutException(String requestId, String commandType) {
        super("Timed out waiting for " + commandType + " response (request " + requestId + ")");
    }
}
