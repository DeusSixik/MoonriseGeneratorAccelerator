package dev.sixik.generator_accelerator.common.density.compiler.cache;

/**
 * Lazy bridge for vanilla {@code NoiseChunk.CacheAllInCell} wrappers whose
 * original filler was not compiled by the normal router pipeline.
 *
 * <p>This path is intentionally opt-in only. Cache wrappers are chunk-local, so
 * compiling them during worldgen can create thousands of short-lived, exact-identity
 * compiled roots and stall world loading.
 */
public interface DfcCellCacheCompiledFillerAccess {
    DfcCellFillAccess dfc$getOrCompileCellFiller();
}
