package io.github.idoly.pi;

import io.github.idoly.pi.core.PiException;
import io.github.idoly.pi.protocol.PiResponse;

/** Pi returned success=false for a request. */
public final class RequestRejectedException extends PiException {
    private final PiResponse response;
    public RequestRejectedException(PiResponse response) {
        super("pi rejected " + response.command() + ": " + response.error());
        this.response = response;
    }
    public PiResponse response() { return response; }
}
