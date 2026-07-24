package dev.sixik.generator_accelerator.common.density.compiler.compiler.plan;

public record SplineSearchStats(
        int multipoints,
        int binaryUsed,
        int autoEligible,
        int maxPoints,
        int bucketLe2,
        int bucket3To4,
        int bucket5To8,
        int bucketGe9) {
}
