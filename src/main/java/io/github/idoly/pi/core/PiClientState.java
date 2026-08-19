package io.github.idoly.pi.core;

/** Lifecycle states of a {@link PiClient}. */
public enum PiClientState {
    STARTING,
    IDLE,
    ACTIVE,
    STOPPING,
    TERMINATED,
    FAILED
}
