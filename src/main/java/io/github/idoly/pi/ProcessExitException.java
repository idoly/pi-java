package io.github.idoly.pi;

import io.github.idoly.pi.core.PiException;

/** Pi exited while its client was still open. */
public final class ProcessExitException extends PiException {
    private final int exitCode;
    public ProcessExitException(int exitCode) {
        super("pi process exited with code " + exitCode);
        this.exitCode = exitCode;
    }
    public int exitCode() { return exitCode; }
}
