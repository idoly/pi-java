package io.github.idoly.pi.core;

/** Indicates that the pi process exited while its client was still open. */
public final class PiProcessExitException extends PiException {
    private final int exitCode;

    public PiProcessExitException(int exitCode) {
        super("pi process exited with code " + exitCode);
        this.exitCode = exitCode;
    }

    /** Returns the operating-system process exit code. */
    public int exitCode() {
        return exitCode;
    }
}
