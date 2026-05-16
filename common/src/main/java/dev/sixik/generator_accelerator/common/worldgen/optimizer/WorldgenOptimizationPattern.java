package dev.sixik.generator_accelerator.common.worldgen.optimizer;

/** High-value vanilla-like shapes that can become detached data/generated plans. */
public enum WorldgenOptimizationPattern {
    ORE_LIKE,
    DISK_OR_BLOB_LIKE,
    RANDOM_PATCH,
    SIMPLE_BLOCK,
    SPRING_OR_LIQUID,
    VEGETATION_OR_WATER_PLANT,
    SELECTOR,
    PLACEMENT_CHAIN,
    SIMPLE_BLOCK_PREDICATE,
    NEIGHBORHOOD_BLOCK_CHECK,
    STREAM_POSITION_PIPELINE,
    PURE_DENSITY_OR_SURFACE,
    NONE
}
