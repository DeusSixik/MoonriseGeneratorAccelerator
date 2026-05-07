package dev.sixik.generator_accelerator.common.density.compiler.cache;

/**
 * Lazy bridge for vanilla {@code NoiseChunk.CacheAllInCell} wrappers whose
 * original filler was not compiled by the normal router pipeline.
 */
public interface DfcCellCacheCompiledFillerAccess {
    DfcCellFillAccess dfc$getOrCompileCellFiller();
}
