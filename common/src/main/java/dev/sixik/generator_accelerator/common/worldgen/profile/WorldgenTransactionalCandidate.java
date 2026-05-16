package dev.sixik.generator_accelerator.common.worldgen.profile;

/**
 * Explicit opt-in marker for unknown worldgen units that may be attempted in a
 * future transaction sandbox. The classifier still treats this as metadata; it
 * must not execute directly against live chunk/world objects.
 */
public interface WorldgenTransactionalCandidate {
    default boolean gaCanUseTransactionalSandbox() {
        return true;
    }
}
