package dev.sixik.generator_accelerator.common.density.compiler.cache;

/**
 * Marker for cache wrappers whose mutable fast state belongs to one worldgen thread.
 */
public interface DfcThreadOwnedCacheAccess {

    boolean dfc$isOwnedByCurrentThread();
}
