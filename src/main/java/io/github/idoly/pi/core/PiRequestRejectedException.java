package io.github.idoly.pi.core;

import io.github.idoly.pi.protocol.PiResponse;

/** Indicates that pi returned an unsuccessful response for an RPC command. */
public final class PiRequestRejectedException extends PiException {
    private final PiResponse response;

    public PiRequestRejectedException(PiResponse response) {
        super("pi rejected " + response.command() + ": " + response.error());
        this.response = response;
    }

    /** Returns the rejected response. */
    public PiResponse response() {
        return response;
    }
}
