package io.github.idoly.pi;

import io.github.idoly.pi.core.PiException;

/** An activity did not settle before its deadline. */
public final class ActivityTimeoutException extends PiException {
    public ActivityTimeoutException(String requestId) {
        super("Timed out waiting for agent_settled (root request " + requestId + ")");
    }
}
