package io.github.idoly.pi.core;

/** Indicates that an activity did not reach {@code agent_settled} before its timeout. */
public final class PiActivityTimeoutException extends PiException {
    public PiActivityTimeoutException(String requestId) {
        super("Timed out waiting for agent_settled (root request " + requestId + ")");
    }
}
