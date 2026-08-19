package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiException;

final class PiProtocolException extends PiException {
    PiProtocolException(String message) {
        super(message);
    }

    PiProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
