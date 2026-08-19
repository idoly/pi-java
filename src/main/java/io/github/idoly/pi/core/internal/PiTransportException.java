package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiException;

final class PiTransportException extends PiException {
    PiTransportException(String message) {
        super(message);
    }

    PiTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
