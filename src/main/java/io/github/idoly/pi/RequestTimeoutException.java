package io.github.idoly.pi;

import io.github.idoly.pi.core.PiException;

/** An RPC response did not arrive before its deadline. */
public final class RequestTimeoutException extends PiException {
    public RequestTimeoutException(String requestId, String command) {
        super("Timed out waiting for " + command + " response (request " + requestId + ")");
    }
}
