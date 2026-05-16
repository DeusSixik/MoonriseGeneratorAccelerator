package dev.sixik.generator_accelerator.common.worldgen.optimizer;

public record WorldgenParitySample(
        String unitId,
        long originalTraceHash,
        long optimizedTraceHash,
        boolean matched
) {
    public WorldgenParitySample {
        unitId = unitId == null ? "" : unitId;
    }

    public static WorldgenParitySample compare(String unitId, long originalTraceHash, long optimizedTraceHash) {
        return new WorldgenParitySample(unitId, originalTraceHash, optimizedTraceHash,
                originalTraceHash == optimizedTraceHash);
    }
}
