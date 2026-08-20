package io.github.idoly.pi.core;

/** Controls which host and project resources the pi subprocess may load. */
public enum PiResourcePolicy {
    /** Disables context files, extensions, skills, prompt templates, and themes. */
    ISOLATED,

    /** Loads global resources while ignoring project-local settings and resources. */
    GLOBAL_ONLY,

    /** Explicitly trusts and loads eligible project-local settings and resources. */
    TRUST_PROJECT
}
