package dev.sixik.generator_accelerator.common.worldgen.lifecycle;

public record GAOuterLifecycleSnapshot(
        long lightingHandoffs,
        long dirtyLightColumns,
        long serializationBatches,
        long serializedChunks,
        long promotionAllows,
        long promotionDefers,
        long promotionFallbacks
) {
}
